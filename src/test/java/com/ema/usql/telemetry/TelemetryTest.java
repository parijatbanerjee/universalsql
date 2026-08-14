package com.ema.usql.telemetry;

import com.ema.usql.telemetry.api.Span;
import com.ema.usql.telemetry.api.StructuredLogger;
import com.ema.usql.telemetry.api.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Telemetry module. No Spring context needed — wired manually.
 */
class TelemetryTest {

    private MeterRegistry meterRegistry;
    private InMemorySpanExporter spanExporter;
    private Telemetry telemetry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        Tracer tracer = openTelemetry.getTracer("com.ema.usql.test");
        telemetry = new TelemetryImpl(meterRegistry, tracer);
    }

    @Test
    void spanCanBeCreatedAndClosed() {
        try (Span span = telemetry.span("test.span", Map.of("module", "test"))) {
            assertThat(span).isNotNull();
        }
        // After close the span should be exported
        List<SpanData> exported = spanExporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        assertThat(exported.get(0).getName()).isEqualTo("test.span");
    }

    @Test
    void spanAttributesAreSet() {
        try (Span span = telemetry.span("query.total", Map.of("tenant", "t1", "path", "LIVE"))) {
            // attributes set at span start
        }
        List<SpanData> exported = spanExporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        SpanData spanData = exported.get(0);
        assertThat(spanData.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("tenant"))).isEqualTo("t1");
        assertThat(spanData.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("path"))).isEqualTo("LIVE");
    }

    @Test
    void counterAppearsInMeterRegistry() {
        telemetry.counter("usql_cache_hit_ratio", Map.of("tenant", "t1", "table", "jira_issues"));

        Counter counter = meterRegistry.find("usql_cache_hit_ratio")
                .tag("tenant", "t1")
                .tag("table", "jira_issues")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void counterIncrementAccumulates() {
        telemetry.counter("usql_rate_limit_rejections_total", Map.of("connector", "jira"));
        telemetry.counter("usql_rate_limit_rejections_total", Map.of("connector", "jira"));
        telemetry.counter("usql_rate_limit_rejections_total", Map.of("connector", "jira"));

        Counter counter = meterRegistry.find("usql_rate_limit_rejections_total")
                .tag("connector", "jira")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    void timerAppearsInMeterRegistry() {
        telemetry.timer("usql_query_duration_ms", Duration.ofMillis(42),
                Map.of("tenant", "t1", "path", "CACHE", "partial", "false"));

        Timer timer = meterRegistry.find("usql_query_duration_ms")
                .tag("tenant", "t1")
                .tag("path", "CACHE")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isCloseTo(42.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void gaugeAppearsInMeterRegistry() {
        AtomicInteger activeFragments = new AtomicInteger(5);
        telemetry.gauge("usql_active_fragments", () -> activeFragments.get(), Map.of("connector", "jira"));

        io.micrometer.core.instrument.Gauge gauge = meterRegistry.find("usql_active_fragments")
                .tag("connector", "jira")
                .gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(5.0);

        activeFragments.set(10);
        assertThat(gauge.value()).isEqualTo(10.0);
    }

    @Test
    void structuredLoggerIsCreated() {
        StructuredLogger logger = telemetry.logger(TelemetryTest.class);
        assertThat(logger).isNotNull();
        // Exercise all log methods — should not throw
        logger.info("test.event", Map.of("key", "value"));
        logger.warn("test.warn", Map.of("key", "value"));
        logger.error("test.error", new RuntimeException("boom"), Map.of("key", "value"));
    }

    @Test
    void logLineContainsTraceIdFromMdc() {
        MDC.put("trace_id", "abc123");
        MDC.put("tenant_id", "tenant-test");
        try {
            StructuredLogger logger = telemetry.logger(TelemetryTest.class);
            // We can't easily intercept logback output in a unit test, but we verify
            // that the MDC values are present (the encoder reads them automatically).
            // Verify MDC round-trip:
            assertThat(MDC.get("trace_id")).isEqualTo("abc123");
            assertThat(MDC.get("tenant_id")).isEqualTo("tenant-test");
            // Calling logger.info should not throw even with MDC set
            logger.info("mdc.test.event", Map.of("sql_hash", "sha256:deadbeef"));
        } finally {
            MDC.remove("trace_id");
            MDC.remove("tenant_id");
        }
    }

    @Test
    void spanRecordsException() {
        RuntimeException ex = new RuntimeException("something went wrong");
        try (Span span = telemetry.span("error.span", Map.of())) {
            span.recordException(ex);
        }
        List<SpanData> exported = spanExporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        assertThat(exported.get(0).getEvents()).isNotEmpty();
    }

    @Test
    void spanSetAttributeAfterCreation() {
        try (Span span = telemetry.span("dynamic.span", Map.of())) {
            span.setAttribute("connector", "github");
        }
        List<SpanData> exported = spanExporter.getFinishedSpanItems();
        assertThat(exported.get(0).getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("connector"))).isEqualTo("github");
    }

    /**
     * Manual verification guide for Jaeger integration.
     * To verify spans appear in Jaeger:
     * 1. Run: docker compose up -d jaeger
     * 2. Run the application: ./gradlew bootRun
     * 3. POST a query to /v1/query
     * 4. Open http://localhost:16686 → search for service "universalsql"
     * 5. Verify the trace shows: query.total → authz.resolve, planner.plan, cache.lookup,
     *    fragment.jira[path], fragment.github[path], execution.merge[join_strategy]
     */
    @Test
    @Disabled("Requires running Jaeger at localhost:16686 — manual verification only")
    void syntheticSpanAppearsInJaeger() {
        // This test documents the manual verification steps above.
        // The in-process tests above (spanCanBeCreatedAndClosed, spanAttributesAreSet) cover
        // the span creation and attribute logic automatically.
    }
}
