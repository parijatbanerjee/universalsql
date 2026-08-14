package com.ema.usql.updates;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration for the updates module.
 * Enables Spring's @Scheduled task support.
 * Can be disabled by setting usql.scheduling.enabled=false (useful in tests).
 * PeriodicUpdater, UpdatesHandler, and JobStateStore are @Service/@RestController beans
 * and are auto-detected by Spring's component scan.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "usql.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class UpdatesConfig {
}
