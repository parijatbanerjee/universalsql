package com.ema.usql.api;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;

/**
 * Generates an ephemeral RSA key pair on startup and exposes a JWKS endpoint
 * at GET /mock-jwks when {@code usql.auth.mock-enabled=true}.
 *
 * <p>The public key is used by SecurityConfig to validate JWT signatures.
 * The private key can be used by tests to mint valid tokens.
 */
@Configuration
@ConditionalOnProperty(name = "usql.auth.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockJwksConfig {

    private final RSAKey rsaKey;

    public MockJwksConfig() {
        try {
            this.rsaKey = new RSAKeyGenerator(2048)
                    .keyID("mock-key-1")
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate mock RSA key pair", e);
        }
    }

    /**
     * The public RSA key used by NimbusJwtDecoder to verify token signatures.
     */
    @Bean
    public RSAPublicKey mockRsaPublicKey() throws Exception {
        return rsaKey.toRSAPublicKey();
    }

    /**
     * The full RSA key (including private) exposed for test token minting.
     */
    @Bean
    public RSAKey mockRsaKey() {
        return rsaKey;
    }

    /**
     * JWKS endpoint — exposes the public key in JWK Set format.
     */
    @RestController
    public class JwksController {

        @GetMapping("/mock-jwks")
        public String jwks() {
            JWKSet jwkSet = new JWKSet(rsaKey.toPublicJWK());
            return jwkSet.toString();
        }
    }
}
