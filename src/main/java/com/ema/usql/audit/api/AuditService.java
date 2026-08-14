package com.ema.usql.audit.api;

/**
 * Durable access trail. Records every access decision including denials.
 */
public interface AuditService {

    /**
     * Durably record an access event.
     */
    void record(AuditEvent event);
}
