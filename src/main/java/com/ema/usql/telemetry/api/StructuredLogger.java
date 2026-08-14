package com.ema.usql.telemetry.api;

import java.util.Map;

/**
 * Structured logging facade. Every line produced by implementations must include
 * trace_id, span_id, tenant_id, user_id from MDC, plus the supplied fields.
 * Never log row values, tokens, key material, or full SQL with literals.
 */
public interface StructuredLogger {

    void info(String event, Map<String, Object> fields);

    void warn(String event, Map<String, Object> fields);

    void error(String event, Throwable t, Map<String, Object> fields);
}
