package com.ema.usql.planner;

import com.ema.usql.planner.catalog.InMemorySourceCatalog;
import com.ema.usql.planner.catalog.SourceCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the planner module.
 * Registers SqlParser and SourceCatalog as beans.
 */
@Configuration
public class PlannerConfig {

    @Bean
    public SourceCatalog sourceCatalog() {
        return new InMemorySourceCatalog();
    }

    @Bean
    public SqlParser sqlParser() {
        return new SqlParser();
    }
}
