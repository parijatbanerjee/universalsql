package com.ema.usql.telemetry;

import com.ema.usql.telemetry.api.Span;

/**
 * Wraps an OpenTelemetry span as the Telemetry API Span.
 * Closing this span ends the underlying OTel span and removes it from the current context.
 */
class SpanImpl implements Span {

    private final io.opentelemetry.api.trace.Span otelSpan;
    private final io.opentelemetry.context.Scope scope;

    SpanImpl(io.opentelemetry.api.trace.Span otelSpan, io.opentelemetry.context.Scope scope) {
        this.otelSpan = otelSpan;
        this.scope = scope;
    }

    @Override
    public void recordException(Throwable t) {
        otelSpan.recordException(t);
    }

    @Override
    public void setAttribute(String key, String value) {
        otelSpan.setAttribute(key, value);
    }

    @Override
    public void close() {
        try {
            scope.close();
        } finally {
            otelSpan.end();
        }
    }

    /** Expose the underlying OTel span for testing. */
    io.opentelemetry.api.trace.Span getOtelSpan() {
        return otelSpan;
    }
}
