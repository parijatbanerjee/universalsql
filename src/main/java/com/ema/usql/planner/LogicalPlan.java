package com.ema.usql.planner;

import java.util.List;
import java.util.Map;

/**
 * The logical representation of a validated SQL query.
 * Produced by SqlParser and consumed by the coordinator to build physical fragments.
 */
public record LogicalPlan(
        List<String> projections,       // column names or ["*"]
        List<String> tables,            // table names referenced
        String joinCondition,           // null if no join, else "t1.col = t2.col"
        String joinTable,               // null if no join, else the joined table name
        String whereClause,             // original WHERE clause string (before RLS injection)
        String orderBy,                 // null or "col ASC/DESC"
        Integer limit,                  // null or limit value
        Map<String, String> tableAliases // alias -> actual table name
) {
}
