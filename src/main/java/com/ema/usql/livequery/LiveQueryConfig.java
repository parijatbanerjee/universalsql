package com.ema.usql.livequery;

import com.ema.usql.livequery.api.LiveQueryService;
import com.ema.usql.sourcegateway.api.SourceGateway;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Live Query Engine module.
 */
@Configuration
public class LiveQueryConfig {

    @Bean
    public LiveQueryService liveQueryService(SourceGateway sourceGateway, Telemetry telemetry) {
        return new LiveQueryEngineImpl(sourceGateway, telemetry);
    }
}
