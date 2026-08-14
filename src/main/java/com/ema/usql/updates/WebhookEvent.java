package com.ema.usql.updates;

/**
 * Incoming webhook event payload from a source system.
 */
public record WebhookEvent(
        String tenantId,
        String resourceId,
        String eventType,
        java.util.Map<String, Object> payload
) {
}
