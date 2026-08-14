package com.ema.usql.knowledgecache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages one DuckDB JDBC connection per tenant, opened lazily on first access.
 * Initializes the schema (jira_issues, github_prs, watermark) on first open.
 * Connections are cached in a thread-safe map; initialization is synchronized per tenantId.
 */
public class TenantDuckDbRegistry {

    private static final String DUCKDB_DRIVER = "org.duckdb.DuckDBDriver";
    private static final String DB_FILENAME = "knowledge.duckdb";

    private final Path baseDir;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();

    public TenantDuckDbRegistry(Path baseDir) {
        this.baseDir = baseDir;
        // Ensure DuckDB driver is loaded
        try {
            Class.forName(DUCKDB_DRIVER);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DuckDB JDBC driver not found on classpath", e);
        }
    }

    /**
     * Return an open JDBC Connection for the given tenant.
     * Creates the database and schema on first access.
     */
    public Connection getConnection(String tenantId) {
        return connections.computeIfAbsent(tenantId, this::openAndInit);
    }

    /**
     * Close and remove the connection for a tenant (e.g. for cleanup in tests).
     */
    public void close(String tenantId) {
        Connection conn = connections.remove(tenantId);
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // Best-effort close
            }
        }
    }

    /**
     * Close all connections (called at shutdown).
     */
    public void closeAll() {
        connections.keySet().forEach(this::close);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Connection openAndInit(String tenantId) {
        synchronized (("duckdb-init-" + tenantId).intern()) {
            // Double-check after acquiring lock
            Connection existing = connections.get(tenantId);
            if (existing != null) {
                return existing;
            }

            Path tenantDir = baseDir.resolve("tenants").resolve(tenantId);
            try {
                Files.createDirectories(tenantDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create tenant DuckDB directory", e);
            }

            Path dbFile = tenantDir.resolve(DB_FILENAME);
            String jdbcUrl = "jdbc:duckdb:" + dbFile.toAbsolutePath();

            try {
                Connection conn = DriverManager.getConnection(jdbcUrl);
                initSchema(conn);
                return conn;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to open DuckDB for tenant: " + tenantId, e);
            }
        }
    }

    private void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS jira_issues (
                        issue_key VARCHAR PRIMARY KEY,
                        project_key VARCHAR NOT NULL,
                        status VARCHAR,
                        priority VARCHAR,
                        assignee_id VARCHAR,
                        reporter_email_enc BLOB,
                        summary VARCHAR,
                        created_at VARCHAR,
                        updated_at VARCHAR,
                        acl_principals VARCHAR[],
                        sourced_at VARCHAR,
                        wrapped_dek BLOB
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS github_prs (
                        pr_number INTEGER PRIMARY KEY,
                        repo VARCHAR NOT NULL,
                        title VARCHAR,
                        state VARCHAR,
                        author_id VARCHAR,
                        author_email_enc BLOB,
                        linked_issue_key VARCHAR,
                        created_at VARCHAR,
                        updated_at VARCHAR,
                        acl_principals VARCHAR[],
                        sourced_at VARCHAR,
                        wrapped_dek BLOB
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS watermark (
                        source VARCHAR NOT NULL,
                        table_name VARCHAR NOT NULL,
                        last_synced_at VARCHAR,
                        last_cursor VARCHAR,
                        PRIMARY KEY (source, table_name)
                    )
                    """);
        }
    }
}
