package com.ema.usql.telemetry;

import com.ema.usql.telemetry.api.Telemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Telemetry module.
 * Exposes the Telemetry facade as a singleton bean.
 * All other modules inject Telemetry — never Micrometer or OTel directly.
 */
@Configuration
public class TelemetryConfig {

    private static final String INSTRUMENTATION_SCOPE = "com.ema.usql";

    @Bean
    public Tracer usqlTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    @Bean
    public Telemetry telemetry(MeterRegistry meterRegistry, Tracer usqlTracer) {
        return new TelemetryImpl(meterRegistry, usqlTracer);
    }
}
