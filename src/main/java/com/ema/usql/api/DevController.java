package com.ema.usql.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * Dev-only token endpoint — issues a signed JWT for load testing and local development.
 *
 * <p>Only active when {@code usql.auth.mock-enabled=true} (the default in dev/test environments).
 * This endpoint MUST NOT be present in production ({@code usql.auth.mock-enabled=false}).
 *
 * <p>Usage: {@code GET /dev/token?userId=alice&tenantId=acme}
 */
@RestController
@RequestMapping("/dev")
@ConditionalOnProperty(name = "usql.auth.mock-enabled", havingValue = "true", matchIfMissing = true)
public class DevController {

    private final RSAKey mockRsaKey;

    public DevController(RSAKey mockRsaKey) {
        this.mockRsaKey = mockRsaKey;
    }

    /**
     * Generate a signed JWT valid for 1 hour.
     *
     * @param userId   the subject claim (user identity)
     * @param tenantId the tenant_id claim
     * @return the serialized JWT string
     */
    @GetMapping("/token")
    public String getToken(
            @RequestParam(defaultValue = "alice") String userId,
            @RequestParam(defaultValue = "acme") String tenantId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId)
                    .claim("tenant_id", tenantId)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(mockRsaKey.getKeyID())
                            .build(),
                    claims
            );

            jwt.sign(new RSASSASigner(mockRsaKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate dev token", e);
        }
    }
}
