package com.ema.usql.authz.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Loads ACL snapshots from the {@code resource_acl} Postgres table.
 * The snapshot captures the current ACL version and sync timestamp
 * for a given tenant/user, enabling staleness detection.
 */
@Service
public class AclStore {

    private final JdbcTemplate jdbcTemplate;

    public AclStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the current ACL snapshot for the given tenant/user.
     * If no ACL entries exist, returns a snapshot with version "0" and epoch sync time.
     */
    public AclSnapshot getSnapshot(String tenantId, String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT MAX(acl_version) AS max_version, MAX(acl_synced_at) AS max_synced
                FROM resource_acl
                WHERE tenant_id = ? AND principal_id IN (
                    SELECT principal_id FROM principal_closure WHERE tenant_id = ? AND user_id = ?
                )
                """,
                tenantId, tenantId, userId
        );

        if (rows.isEmpty() || rows.get(0).get("max_version") == null) {
            // No ACL data: treat as "just synced now" (fresh) to avoid forcing LIVE path
            // when no resource_acl rows exist for this user.
            return new AclSnapshot("0", Instant.now());
        }

        Map<String, Object> row = rows.get(0);
        long version = ((Number) row.get("max_version")).longValue();
        Timestamp ts = (Timestamp) row.get("max_synced");
        Instant syncedAt = ts != null ? ts.toInstant() : Instant.EPOCH;

        return new AclSnapshot(String.valueOf(version), syncedAt);
    }
}
