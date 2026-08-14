package com.ema.usql.updates;

import com.ema.usql.connectors.ConnectorRegistry;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.connectors.api.ConnectorSdk;
import com.ema.usql.connectors.api.Credential;
import com.ema.usql.connectors.api.SourceQuery;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.telemetry.api.StructuredLogger;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scheduled service that periodically syncs data from source connectors
 * into the per-tenant DuckDB knowledge cache.
 *
 * Runs every 60 seconds (configurable via fixedDelay).
 * Each run fetches from all registered connectors for all active tenants.
 * Failures for one tenant/connector do not block others.
 */
@Service
public class PeriodicUpdater {

    // Table mapping for connectors
    private static final Map<String, String> CONNECTOR_TABLES = Map.of(
            "jira", "jira_issues",
            "github", "github_prs"
    );

    private final ConnectorRegistry connectorRegistry;
    private final KnowledgeCacheService knowledgeCacheService;
    private final WatermarkStore watermarkStore;
    private final JobStateStore jobStateStore;
    private final JdbcTemplate jdbc;
    private final Telemetry telemetry;
    private final StructuredLogger log;

    public PeriodicUpdater(
            ConnectorRegistry connectorRegistry,
            KnowledgeCacheService knowledgeCacheService,
            WatermarkStore watermarkStore,
            JobStateStore jobStateStore,
            JdbcTemplate jdbc,
            Telemetry telemetry) {
        this.connectorRegistry = connectorRegistry;
        this.knowledgeCacheService = knowledgeCacheService;
        this.watermarkStore = watermarkStore;
        this.jobStateStore = jobStateStore;
        this.jdbc = jdbc;
        this.telemetry = telemetry;
        this.log = telemetry.logger(PeriodicUpdater.class);
    }

    /**
     * Scheduled sync trigger. The scheduler can be disabled in tests via
     * spring.task.scheduling.enabled=false or by not calling this method.
     */
    @Scheduled(fixedDelay = 60000)
    public void scheduledUpdate() {
        runUpdate();
    }

    /**
     * Execute one full sync cycle across all active tenants and connectors.
     * Can also be called directly in tests.
     */
    public void runUpdate() {
        List<String> tenantIds = fetchActiveTenantIds();

        for (String tenantId : tenantIds) {
            for (String connectorId : connectorRegistry.connectorIds()) {
                try {
                    syncConnector(tenantId, connectorId);
                } catch (Exception e) {
                    log.error("Sync failed for tenant/connector",
                            e, Map.of("tenantId", tenantId, "connectorId", connectorId));
                }
            }
        }
    }

    private void syncConnector(String tenantId, String connectorId) {
        String tableName = CONNECTOR_TABLES.getOrDefault(connectorId, connectorId);
        String jobId = tenantId + ":" + connectorId;

        // Ensure job record exists
        jobStateStore.createJob(jobId, tenantId, connectorId, "PERIODIC_SYNC");

        // Get current watermark
        Watermark watermark = knowledgeCacheService.getWatermark(connectorId, tableName, tenantId);

        // Build a query. In a real system this would use JQL/GraphQL based on watermark.
        String jql = "project IS NOT EMPTY";
        SourceQuery query = new SourceQuery(connectorId, jql, List.of(), 30000);

        // Use a system credential (real credential would come from oauth_connection table)
        Credential cred = new Credential("system-" + tenantId + "-" + connectorId);

        ConnectorSdk connector = connectorRegistry.getConnector(connectorId);

        // Fetch records
        List<ConnectorRecord> records = connector.fetch(query, cred);

        if (!records.isEmpty()) {
            TenantContext ctx = new TenantContext(tenantId, "system", tenantId + "-kek");
            knowledgeCacheService.write(records, ctx);
        }

        // Advance watermark in DuckDB
        Instant now = Instant.now();
        watermarkStore.updateWatermark(connectorId, tableName, tenantId, now, now.toString());

        // Update job state in Postgres
        jobStateStore.updateWatermark(jobId, now.toString(), now, "IDLE");

        log.info("Sync complete",
                Map.of("tenantId", tenantId, "connectorId", connectorId,
                        "recordCount", records.size()));
    }

    private List<String> fetchActiveTenantIds() {
        return jdbc.queryForList(
                "SELECT tenant_id FROM tenant WHERE status = 'active'",
                String.class);
    }
}
