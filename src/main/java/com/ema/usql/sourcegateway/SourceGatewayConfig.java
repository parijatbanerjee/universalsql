package com.ema.usql.sourcegateway;

import com.ema.usql.authz.api.TokenService;
import com.ema.usql.connectors.ConnectorRegistry;
import com.ema.usql.sourcegateway.api.SourceGateway;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Source Gateway module.
 * Registers the SourceGatewayImpl bean, wiring TokenService (authz.api),
 * ConnectorRegistry, and Telemetry.
 */
@Configuration
public class SourceGatewayConfig {

    @Bean
    public SourceGateway sourceGateway(TokenService tokenService,
                                       ConnectorRegistry connectorRegistry,
                                       Telemetry telemetry) {
        return new SourceGatewayImpl(tokenService, connectorRegistry, telemetry);
    }
}
