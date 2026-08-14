package com.ema.usql.audit.api;

import java.util.List;

/**
 * An immutable record of an access decision.
 * Never contains row values, tokens, or key material.
 * sql_hash is a hash of the SQL statement with literals removed.
 */
public record AuditEvent(
        String traceId,
        String tenantId,
        String userId,
        String connectorId,
        String action,
        List<String> resourceIds,
        String decision,
        String reason,
        String sqlHash
) {
}
