package com.ema.usql.knowledgecache;

import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring configuration for the knowledge cache module.
 * Wires TenantDuckDbRegistry, WatermarkStore, and KnowledgeCacheServiceImpl.
 */
@Configuration
public class KnowledgeCacheConfig {

    private static final String DATA_DIR = "data";

    @Bean
    public TenantDuckDbRegistry tenantDuckDbRegistry() {
        Path baseDir = Paths.get(DATA_DIR);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create data directory", e);
        }
        return new TenantDuckDbRegistry(baseDir);
    }

    @Bean
    public WatermarkStore watermarkStore(TenantDuckDbRegistry registry) {
        return new WatermarkStore(registry);
    }

    @Bean
    public KnowledgeCacheServiceImpl knowledgeCacheService(
            TenantDuckDbRegistry registry,
            WatermarkStore watermarkStore,
            KmsModule kmsModule,
            Telemetry telemetry) {
        return new KnowledgeCacheServiceImpl(registry, watermarkStore, kmsModule, telemetry);
    }
}
