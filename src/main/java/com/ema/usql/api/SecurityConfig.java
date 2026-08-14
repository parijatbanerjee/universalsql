package com.ema.usql.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;

/**
 * Security configuration for the HTTP layer.
 *
 * <p>When {@code usql.auth.mock-enabled=true} (the default), uses the local ephemeral RSA
 * key pair from {@link MockJwksConfig} for JWT validation. This enables integration testing
 * without an external OIDC provider.
 *
 * <p>When {@code usql.auth.mock-enabled=false}, the standard Spring Security OAuth2 resource
 * server auto-configuration kicks in using the configured JWKS URI.
 *
 * <p>Actuator health and prometheus endpoints are always public.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health", "/actuator/prometheus",
                    "/mock-jwks",
                    "/dev/token",    // dev-only; DevController only active with mock-enabled=true
                    "/admin/v1/**"   // admin endpoints use X-Admin-Key header, not JWT
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
            )
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * When mock mode is enabled, decode JWTs using the local ephemeral RSA public key.
     * This eliminates the need for a live OIDC provider during development and testing.
     */
    @Bean
    @ConditionalOnProperty(name = "usql.auth.mock-enabled", havingValue = "true", matchIfMissing = true)
    public JwtDecoder mockJwtDecoder(RSAPublicKey mockRsaPublicKey) {
        return NimbusJwtDecoder.withPublicKey(mockRsaPublicKey).build();
    }
}
