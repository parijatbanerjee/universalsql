package com.ema.usql.updates;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Manages job state records in the PostgreSQL job_state table.
 * Tracks the sync status and watermark for each (tenant, connector) pair.
 */
@Service
public class JobStateStore {

    private final JdbcTemplate jdbc;

    public JobStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a new job record if it doesn't already exist.
     */
    public void createJob(String jobId, String tenantId, String connectorId, String kind) {
        jdbc.update("""
                INSERT INTO job_state (job_id, tenant_id, connector_id, kind, status)
                VALUES (?, ?, ?, ?, 'IDLE')
                ON CONFLICT (job_id) DO NOTHING
                """,
                jobId, tenantId, connectorId, kind);
    }

    /**
     * Update job state with new watermark and status after a sync run.
     */
    public void updateWatermark(String jobId, String watermark, Instant lastRunAt, String status) {
        jdbc.update("""
                UPDATE job_state
                SET watermark = ?, last_run_at = ?, status = ?
                WHERE job_id = ?
                """,
                watermark,
                lastRunAt != null ? java.sql.Timestamp.from(lastRunAt) : null,
                status,
                jobId);
    }
}
