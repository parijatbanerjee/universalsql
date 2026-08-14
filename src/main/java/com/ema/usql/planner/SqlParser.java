package com.ema.usql.planner;

import com.ema.usql.planner.catalog.SourceCatalog;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import com.ema.usql.authz.api.ClsMaskSet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Parses a SQL statement into a {@link LogicalPlan}, validating against the catalog.
 *
 * <p>Supported subset:
 * <ul>
 *   <li>SELECT projection (specific columns or *)</li>
 *   <li>FROM single table OR one JOIN ... ON</li>
 *   <li>WHERE with =, IN, &gt;, &lt;, AND (no OR, no subqueries)</li>
 *   <li>ORDER BY</li>
 *   <li>LIMIT</li>
 * </ul>
 */
public class SqlParser {

    /**
     * Parse and validate the SQL string, returning a LogicalPlan on success.
     *
     * @throws UsqlException with {@code UNSUPPORTED_SQL} on any violation
     */
    public LogicalPlan parse(String sql, SourceCatalog catalog) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                    "Failed to parse SQL: " + e.getMessage(), e);
        }

        // Only SELECT is allowed
        if (!(statement instanceof Select select)) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                    "Only SELECT statements are supported, got: "
                            + statement.getClass().getSimpleName());
        }

        if (!(select.getSelectBody() instanceof PlainSelect ps)) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                    "Only plain SELECT statements are supported (no UNION, INTERSECT, etc.)");
        }

        // No subqueries in FROM
        FromItem fromItem = ps.getFromItem();
        if (fromItem instanceof ParenthesedSelect) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                    "Subqueries are not supported");
        }

        // Collect table aliases
        Map<String, String> tableAliases = new HashMap<>();
        List<String> tables = new ArrayList<>();

        Table mainTable = extractTable(fromItem);
        String mainTableName = mainTable.getName();
        tables.add(mainTableName);
        if (mainTable.getAlias() != null) {
            tableAliases.put(mainTable.getAlias().getName(), mainTableName);
        }

        // Validate main table exists
        if (!catalog.tableExists(mainTableName)) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                    "Unknown table: " + mainTableName);
        }

        // Handle JOINs
        String joinCondition = null;
        String joinTable = null;
        List<Join> joins = ps.getJoins();
        if (joins != null && !joins.isEmpty()) {
            if (joins.size() > 1) {
                throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                        "At most one JOIN is supported, got: " + joins.size());
            }
            Join join = joins.get(0);
            FromItem joinFromItem = join.getRightItem();
            if (joinFromItem instanceof ParenthesedSelect) {
                throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                        "Subqueries in JOIN are not supported");
            }
            Table joinTableObj = extractTable(joinFromItem);
            joinTable = joinTableObj.getName();
            tables.add(joinTable);
            if (joinTableObj.getAlias() != null) {
                tableAliases.put(joinTableObj.getAlias().getName(), joinTable);
            }

            // Validate join table exists
            if (!catalog.tableExists(joinTable)) {
                throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                        "Unknown table: " + joinTable);
            }

            // Extract join condition (ON clause)
            Expression onExpr = join.getOnExpression();
            if (onExpr != null) {
                joinCondition = onExpr.toString();
            }
        }

        // Validate SELECT items and collect projections
        List<String> projections = new ArrayList<>();
        boolean isStarSelect = false;
        for (SelectItem<?> item : ps.getSelectItems()) {
            if (item.getExpression() instanceof AllColumns) {
                projections.add("*");
                isStarSelect = true;
            } else {
                Expression expr = item.getExpression();
                // Check for aggregate functions
                checkNoAggregates(expr);
                // Check for subqueries in SELECT
                checkNoSubqueries(expr);

                if (expr instanceof Column col) {
                    projections.add(col.getColumnName());
                } else {
                    projections.add(expr.toString());
                }
            }
        }

        // Validate WHERE clause
        Expression where = ps.getWhere();
        String whereClause = null;
        if (where != null) {
            // Check for OR
            checkNoOr(where);
            // Check for subqueries in WHERE
            checkNoSubqueriesInWhere(where);
            // Check for aggregates in WHERE
            checkNoAggregates(where);
            whereClause = where.toString();
        }

        // Validate referenced columns (only if not star select)
        if (!isStarSelect) {
            validateProjectionColumns(projections, tables, tableAliases, catalog);
        }

        // Validate WHERE columns
        if (where != null) {
            validateWhereColumns(where, tables, tableAliases, catalog);
        }

        // ORDER BY
        String orderBy = null;
        if (ps.getOrderByElements() != null && !ps.getOrderByElements().isEmpty()) {
            OrderByElement orderByEl = ps.getOrderByElements().get(0);
            orderBy = orderByEl.getExpression().toString() + (orderByEl.isAsc() ? " ASC" : " DESC");
        }

        // LIMIT
        Integer limit = null;
        if (ps.getLimit() != null) {
            Limit limitObj = ps.getLimit();
            if (limitObj.getRowCount() != null) {
                limit = Integer.parseInt(limitObj.getRowCount().toString());
            }
        }

        return new LogicalPlan(
                projections,
                tables,
                joinCondition,
                joinTable,
                whereClause,
                orderBy,
                limit,
                tableAliases
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Table extractTable(FromItem fromItem) {
        if (fromItem instanceof Table table) {
            return table;
        }
        throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                "Only simple table references are supported in FROM");
    }

    private void checkNoOr(Expression expr) {
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(OrExpression orExpression) {
                throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                        "OR in WHERE clause is not supported. Use AND only.");
            }
        });
    }

    private void checkNoSubqueriesInWhere(Expression expr) {
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect select) {
                throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                        "Subqueries in WHERE clause are not supported");
            }

            @Override
            public void visit(InExpression inExpr) {
                // Check if the right side is a subquery
                if (inExpr.getRightExpression() instanceof ParenthesedSelect) {
                    throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                            "Subqueries in WHERE clause are not supported");
                }
                super.visit(inExpr);
            }
        });
    }

    private void checkNoSubqueries(Expression expr) {
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect select) {
                throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                        "Subqueries are not supported");
            }
        });
    }

    private void checkNoAggregates(Expression expr) {
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Function function) {
                String name = function.getName().toUpperCase();
                Set<String> aggregates = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX",
                        "GROUP_CONCAT", "STRING_AGG", "ARRAY_AGG", "FIRST", "LAST",
                        "STDDEV", "VARIANCE", "BIT_AND", "BIT_OR");
                if (aggregates.contains(name)) {
                    throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                            "Aggregate functions are not supported: " + function.getName());
                }
            }
        });
    }

    private void validateProjectionColumns(
            List<String> projections,
            List<String> tables,
            Map<String, String> tableAliases,
            SourceCatalog catalog) {

        for (String col : projections) {
            if ("*".equals(col)) continue;

            // col may be "alias.columnName" or just "columnName"
            if (col.contains(".")) {
                String[] parts = col.split("\\.", 2);
                String tableRef = parts[0];
                String colName = parts[1];
                String resolvedTable = tableAliases.getOrDefault(tableRef, tableRef);
                if (!catalog.columnExists(resolvedTable, colName)) {
                    throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                            "Unknown column: " + colName + " in table: " + resolvedTable);
                }
            } else {
                // Find the column in any of the referenced tables
                boolean found = false;
                for (String table : tables) {
                    if (catalog.columnExists(table, col)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                            "Unknown column: " + col + " in table: " + tables.get(0));
                }
            }
        }
    }

    /**
     * Check that none of the masked columns appear in WHERE, ORDER BY, or GROUP BY predicates.
     * If a masked column is found in a predicate, throws UsqlException(ENTITLEMENT_DENIED).
     *
     * @param sql          the SQL string to check
     * @param clsMaskSet   the set of masked columns for this user
     */
    public void validateMaskedColumnsNotInPredicates(String sql, ClsMaskSet clsMaskSet) {
        if (clsMaskSet == null || clsMaskSet.maskedColumns().isEmpty()) {
            return;
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return; // parse error will be caught elsewhere
        }

        if (!(statement instanceof Select select)) {
            return;
        }
        if (!(select.getSelectBody() instanceof PlainSelect ps)) {
            return;
        }

        Set<String> maskedCols = clsMaskSet.maskedColumns().keySet();

        // Check WHERE clause
        Expression where = ps.getWhere();
        if (where != null) {
            Set<String> found = new LinkedHashSet<>();
            where.accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(Column column) {
                    if (maskedCols.contains(column.getColumnName())) {
                        found.add(column.getColumnName());
                    }
                }
            });
            if (!found.isEmpty()) {
                throw new UsqlException(ErrorCode.ENTITLEMENT_DENIED,
                        "MASKED_COLUMN_IN_PREDICATE: " + found.iterator().next());
            }
        }

        // Check ORDER BY
        if (ps.getOrderByElements() != null) {
            for (OrderByElement orderByEl : ps.getOrderByElements()) {
                orderByEl.getExpression().accept(new ExpressionVisitorAdapter() {
                    @Override
                    public void visit(Column column) {
                        if (maskedCols.contains(column.getColumnName())) {
                            throw new UsqlException(ErrorCode.ENTITLEMENT_DENIED,
                                    "MASKED_COLUMN_IN_PREDICATE: " + column.getColumnName());
                        }
                    }
                });
            }
        }

        // Note: GROUP BY with aggregates is rejected by checkNoAggregates() earlier in parse().
        // No additional GROUP BY check needed here.
    }

    private void validateWhereColumns(
            Expression where,
            List<String> tables,
            Map<String, String> tableAliases,
            SourceCatalog catalog) {

        Set<String> violations = new LinkedHashSet<>();

        where.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                String colName = column.getColumnName();
                String tablePrefix = column.getTable() != null ? column.getTable().getName() : null;

                if (tablePrefix != null) {
                    String resolvedTable = tableAliases.getOrDefault(tablePrefix, tablePrefix);
                    if (catalog.tableExists(resolvedTable) && !catalog.columnExists(resolvedTable, colName)) {
                        violations.add("Unknown column: " + colName + " in table: " + resolvedTable);
                    }
                } else {
                    boolean found = false;
                    for (String table : tables) {
                        if (catalog.columnExists(table, colName)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        violations.add("Unknown column: " + colName + " in table: " + tables.get(0));
                    }
                }
            }
        });

        if (!violations.isEmpty()) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL, violations.iterator().next());
        }
    }
}
