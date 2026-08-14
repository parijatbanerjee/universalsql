package com.ema.usql.telemetry.api;

/**
 * An active tracing span. Must be closed (ideally in a try-with-resources block)
 * to mark the span as finished.
 */
public interface Span extends AutoCloseable {

    /** Record an error on this span without closing it. */
    void recordException(Throwable t);

    /** Add a key/value attribute to this span. */
    void setAttribute(String key, String value);

    @Override
    void close();
}
