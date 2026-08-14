package com.ema.usql.audit;

import com.ema.usql.audit.api.AuditEvent;
import com.ema.usql.audit.api.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Durable audit service backed by the Postgres {@code audit_event} table.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Never stores row values, email addresses, or sensitive field content</li>
 *   <li>SQL is stored as a salted SHA-256 hash only</li>
 *   <li>resource_ids store logical identifiers (table names), not row data</li>
 * </ul>
 */
@Service
public class AuditServiceImpl implements AuditService {

    private final JdbcTemplate jdbcTemplate;

    public AuditServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(AuditEvent event) {
        String[] resourceIds = event.resourceIds() != null
                ? event.resourceIds().toArray(new String[0])
                : new String[0];

        jdbcTemplate.update(
                """
                INSERT INTO audit_event
                    (trace_id, tenant_id, user_id, connector_id, action,
                     resource_ids, decision, reason, sql_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.traceId(),
                event.tenantId(),
                event.userId(),
                event.connectorId(),
                event.action(),
                resourceIds,
                event.decision(),
                event.reason(),
                event.sqlHash()
        );
    }
}
