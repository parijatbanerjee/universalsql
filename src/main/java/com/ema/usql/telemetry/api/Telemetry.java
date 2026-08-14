package com.ema.usql.telemetry.api;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Central telemetry facade (spec §10).
 * All modules must use this interface — never import Micrometer or OpenTelemetry directly
 * outside the telemetry module.
 */
public interface Telemetry {

    /**
     * Start a new tracing span with the given name and attributes.
     * The caller must close the returned Span (use try-with-resources).
     */
    Span span(String name, Map<String, String> attrs);

    /** Increment a counter metric. */
    void counter(String name, Map<String, String> tags);

    /** Record a timer observation. */
    void timer(String name, Duration d, Map<String, String> tags);

    /** Register a gauge that pulls its value from the supplier. */
    void gauge(String name, Supplier<Number> v, Map<String, String> tags);

    /** Obtain a structured logger scoped to the given class. */
    StructuredLogger logger(Class<?> clazz);
}
