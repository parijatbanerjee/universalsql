package com.ema.usql.crypto;

import com.ema.usql.crypto.api.KmsModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring configuration for the crypto module.
 * Exposes LocalKmsModule as the KmsModule bean and ensures data/kms/ exists at startup.
 */
@Configuration
public class CryptoConfig {

    private static final String KMS_DIR = "data/kms";

    @Bean
    public KmsModule kmsModule() {
        Path kmsDir = Paths.get(KMS_DIR);
        try {
            Files.createDirectories(kmsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create KMS directory: " + kmsDir, e);
        }
        return new LocalKmsModule(kmsDir);
    }
}
