package com.ema.usql.authz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Loads row/column-level security policies from the {@code policy} Postgres table.
 */
@Service
public class PolicyStore {

    private final JdbcTemplate jdbcTemplate;

    public PolicyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Find the policy for the given tenant and table.
     * Returns {@code null} if no policy is configured.
     */
    public Policy findPolicy(String tenantId, String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id, table_name, rls_expr, cls_json::text AS cls_json, version FROM policy WHERE tenant_id = ? AND table_name = ?",
                tenantId, tableName
        );

        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        return new Policy(
                (String) row.get("tenant_id"),
                (String) row.get("table_name"),
                (String) row.get("rls_expr"),
                (String) row.get("cls_json"),
                row.get("version") != null ? ((Number) row.get("version")).intValue() : 1
        );
    }

    /**
     * Immutable policy record.
     */
    public record Policy(
            String tenantId,
            String tableName,
            String rlsExpr,
            String clsJson,
            int version
    ) {
    }
}
