package com.ema.usql.coordinator.execution;

import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.ResultColumn;
import com.ema.usql.shared.UsqlException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages an in-memory DuckDB connection for join scratch space.
 *
 * <p>Each instance holds a fresh in-memory DuckDB connection (not the per-tenant file).
 * Tables registered here exist only for the lifetime of this session.
 * Must be closed after use to release the connection.
 *
 * <p>Usage:
 * <pre>{@code
 * try (DuckDbSession session = new DuckDbSession()) {
 *     session.registerTable("jira_issues", jiraResult);
 *     session.registerTable("github_prs", githubResult);
 *     QueryResult joined = session.executeJoin(joinSql);
 * }
 * }</pre>
 */
public class DuckDbSession implements AutoCloseable {

    private static final String DUCKDB_DRIVER = "org.duckdb.DuckDBDriver";
    private static final String DUCKDB_IN_MEMORY_URL = "jdbc:duckdb:";

    private final Connection conn;

    public DuckDbSession() {
        try {
            Class.forName(DUCKDB_DRIVER);
            this.conn = DriverManager.getConnection(DUCKDB_IN_MEMORY_URL);
        } catch (ClassNotFoundException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "DuckDB JDBC driver not found on classpath", e);
        } catch (SQLException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to open in-memory DuckDB connection: " + e.getMessage(), e);
        }
    }

    /**
     * Register a QueryResult as an in-memory DuckDB table using batch INSERT.
     *
     * @param tableName the name for the in-memory table
     * @param result    the QueryResult to materialize
     */
    public void registerTable(String tableName, QueryResult result) {
        if (result == null || result.columns().isEmpty()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (_empty VARCHAR)");
            } catch (SQLException e) {
                throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                        "Failed to create empty table " + tableName + ": " + e.getMessage(), e);
            }
            return;
        }

        try {
            // Build CREATE TABLE SQL from column definitions
            StringBuilder createSql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
            createSql.append(tableName).append(" (");
            List<ResultColumn> columns = result.columns();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) createSql.append(", ");
                createSql.append(quoteIdentifier(columns.get(i).name()));
                createSql.append(" VARCHAR"); // Use VARCHAR for all types; safe for in-memory joins
            }
            createSql.append(")");

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createSql.toString());
            }

            if (result.rows().isEmpty()) {
                return;
            }

            // Build INSERT SQL
            StringBuilder insertSql = new StringBuilder("INSERT INTO ");
            insertSql.append(tableName).append(" VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) insertSql.append(", ");
                insertSql.append("?");
            }
            insertSql.append(")");

            try (PreparedStatement ps = conn.prepareStatement(insertSql.toString())) {
                for (List<Object> row : result.rows()) {
                    for (int i = 0; i < columns.size(); i++) {
                        Object val = i < row.size() ? row.get(i) : null;
                        if (val == null) {
                            ps.setNull(i + 1, Types.VARCHAR);
                        } else {
                            ps.setString(i + 1, val.toString());
                        }
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }

        } catch (SQLException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to register table " + tableName + " in DuckDB session: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a JOIN SQL query against the registered in-memory tables.
     *
     * @param sql the SQL query to execute (typically a JOIN)
     * @return the joined QueryResult
     */
    public QueryResult executeJoin(String sql) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            List<ResultColumn> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(new ResultColumn(meta.getColumnLabel(i), "VARCHAR"));
            }

            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }

            return new QueryResult(columns, rows, Map.of());

        } catch (SQLException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to execute join in DuckDB session: " + e.getMessage(), e);
        }
    }

    /**
     * Drop all registered tables and close the connection.
     */
    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            // Best-effort close
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
