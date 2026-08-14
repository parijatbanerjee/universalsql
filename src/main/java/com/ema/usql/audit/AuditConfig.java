package com.ema.usql.audit;

import com.ema.usql.audit.api.AuditService;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the audit module.
 * AuditServiceImpl is already registered via @Service; this class
 * provides a named @Configuration entry point for future extensions.
 */
@Configuration
public class AuditConfig {
    // AuditServiceImpl is a @Service bean — no explicit @Bean methods needed.
    // This config class exists to satisfy the task spec and serve as extension point.
}
