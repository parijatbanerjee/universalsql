package com.ema.usql.updates;

import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.telemetry.api.StructuredLogger;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for receiving webhook events from source systems (Jira, GitHub, etc.).
 * On success: updates the knowledge cache.
 * On error: writes the event to the dlq_event table in Postgres.
 */
@RestController
@RequestMapping("/webhooks")
public class UpdatesHandler {

    private final KnowledgeCacheService knowledgeCacheService;
    private final JdbcTemplate jdbc;
    private final Telemetry telemetry;
    private final StructuredLogger log;

    public UpdatesHandler(
            KnowledgeCacheService knowledgeCacheService,
            JdbcTemplate jdbc,
            Telemetry telemetry) {
        this.knowledgeCacheService = knowledgeCacheService;
        this.jdbc = jdbc;
        this.telemetry = telemetry;
        this.log = telemetry.logger(UpdatesHandler.class);
    }

    @PostMapping("/{connector}")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable String connector,
            @RequestBody WebhookEvent event) {

        try {
            validate(event);
            processEvent(connector, event);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            writeToDlq(connector, event, e);
            log.error("Webhook processing failed; event sent to DLQ",
                    e, Map.of("connector", connector));
            // Return 202 Accepted — the event was received but processing failed
            return ResponseEntity.accepted().body(Map.of("status", "dlq", "reason", e.getMessage()));
        }
    }

    private void validate(WebhookEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event payload is null");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
    }

    private void processEvent(String connectorId, WebhookEvent event) {
        TenantContext ctx = new TenantContext(
                event.tenantId(), "webhook-system", event.tenantId() + "-kek");

        // Build a ConnectorRecord from the event payload
        Map<String, Object> fields = new HashMap<>();
        if (event.payload() != null) {
            fields.putAll(event.payload());
        }

        // Ensure sourced_at is set/updated to now
        fields.put("sourced_at", Instant.now().toString());

        ConnectorRecord record = new ConnectorRecord(fields);
        knowledgeCacheService.write(List.of(record), ctx);
    }

    private void writeToDlq(String connectorId, WebhookEvent event, Exception e) {
        try {
            String tenantId = (event != null && event.tenantId() != null) ? event.tenantId() : "unknown";
            String eventType = (event != null && event.eventType() != null) ? event.eventType() : "unknown";
            String payload = event != null && event.payload() != null
                    ? event.payload().toString()
                    : null;

            jdbc.update("""
                    INSERT INTO dlq_event (tenant_id, connector_id, event_type, payload, error_message)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    tenantId,
                    connectorId,
                    eventType,
                    payload,
                    e.getMessage());
        } catch (Exception dlqEx) {
            // Log DLQ write failure but don't propagate - we don't want to hide the original error
            log.error("Failed to write to DLQ", dlqEx, Map.of("connectorId", connectorId));
        }
    }
}
