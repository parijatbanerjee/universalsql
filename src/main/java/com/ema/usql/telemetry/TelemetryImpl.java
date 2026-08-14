package com.ema.usql.telemetry;

import com.ema.usql.telemetry.api.Span;
import com.ema.usql.telemetry.api.StructuredLogger;
import com.ema.usql.telemetry.api.Telemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opentelemetry.api.trace.Tracer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Telemetry facade implementation backed by Micrometer (metrics) and OpenTelemetry (traces).
 * All modules must depend only on the Telemetry interface — never on Micrometer or OTel directly.
 */
public class TelemetryImpl implements Telemetry {

    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public TelemetryImpl(MeterRegistry meterRegistry, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @Override
    public Span span(String name, Map<String, String> attrs) {
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(name);
        if (attrs != null) {
            attrs.forEach(builder::setAttribute);
        }
        io.opentelemetry.api.trace.Span otelSpan = builder.startSpan();
        io.opentelemetry.context.Scope scope = otelSpan.makeCurrent();
        return new SpanImpl(otelSpan, scope);
    }

    @Override
    public void counter(String name, Map<String, String> tags) {
        meterRegistry.counter(name, toTagList(tags)).increment();
    }

    @Override
    public void timer(String name, Duration d, Map<String, String> tags) {
        meterRegistry.timer(name, toTagList(tags)).record(d.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void gauge(String name, Supplier<Number> v, Map<String, String> tags) {
        // Gauge is registered with the supplier; re-registration is idempotent via Micrometer
        meterRegistry.gauge(name, toTagList(tags), v, supplier -> supplier.get().doubleValue());
    }

    @Override
    public StructuredLogger logger(Class<?> clazz) {
        return new StructuredLoggerImpl(clazz);
    }

    private Iterable<Tag> toTagList(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.entrySet().stream()
                .map(e -> Tag.of(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }
}
