package com.ema.usql.telemetry;

import com.ema.usql.telemetry.api.StructuredLogger;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SLF4J-backed structured logger. Each log line includes trace_id, span_id,
 * tenant_id, and user_id from MDC — injected automatically by Logback's JSON encoder.
 *
 * IMPORTANT: Never log row values, tokens, key material, or full SQL with literals.
 */
class StructuredLoggerImpl implements StructuredLogger {

    private final Logger log;

    StructuredLoggerImpl(Class<?> clazz) {
        this.log = LoggerFactory.getLogger(clazz);
    }

    @Override
    public void info(String event, Map<String, Object> fields) {
        if (log.isInfoEnabled()) {
            log.info(event, buildArgs(fields));
        }
    }

    @Override
    public void warn(String event, Map<String, Object> fields) {
        if (log.isWarnEnabled()) {
            log.warn(event, buildArgs(fields));
        }
    }

    @Override
    public void error(String event, Throwable t, Map<String, Object> fields) {
        if (log.isErrorEnabled()) {
            log.error(event, buildArgs(fields), t);
        }
    }

    private Object[] buildArgs(Map<String, Object> fields) {
        // Build a merged map: MDC context + caller fields
        Map<String, Object> merged = new LinkedHashMap<>();
        String traceId = MDC.get("trace_id");
        String spanId = MDC.get("span_id");
        String tenantId = MDC.get("tenant_id");
        String userId = MDC.get("user_id");

        if (traceId != null) merged.put("trace_id", traceId);
        if (spanId != null) merged.put("span_id", spanId);
        if (tenantId != null) merged.put("tenant_id", tenantId);
        if (userId != null) merged.put("user_id", userId);

        if (fields != null) {
            merged.putAll(fields);
        }

        return new Object[]{StructuredArguments.entries(merged)};
    }
}
