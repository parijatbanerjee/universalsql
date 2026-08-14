package com.ema.usql.authz;

import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.principals.PrincipalStore;
import com.ema.usql.shared.TenantContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10 acceptance tests: AuthZ service and principal resolution.
 *
 * Verifies:
 * - alice resolves to principals containing "project:PLAT" and "project:CORE"
 * - bob resolves to principals containing only "project:CORE"
 * - An expired JWT is rejected (401)
 * - A valid JWT for alice returns 200 OK
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class Task10AuthzTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("usql")
            .withUsername("usql")
            .withPassword("usql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("usql.auth.mock-enabled", () -> "true");
    }

    @Autowired
    private PrincipalStore principalStore;

    @Autowired
    private AuthzServiceImpl authzService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RSAKey mockRsaKey;

    private static final String TENANT_ID = "acme";

    /**
     * Test 1: alice resolves to principals containing "project:PLAT" and "project:CORE".
     */
    @Test
    void aliceResolvesToPlatAndCoreProjects() {
        Set<String> principals = principalStore.getPrincipals(TENANT_ID, "alice");
        assertThat(principals).contains("project:PLAT", "project:CORE");
    }

    /**
     * Test 2: bob resolves to principals containing only "project:CORE".
     */
    @Test
    void bobResolvesToCoreOnly() {
        Set<String> principals = principalStore.getPrincipals(TENANT_ID, "bob");
        assertThat(principals).contains("project:CORE");
        assertThat(principals).doesNotContain("project:PLAT");
    }

    /**
     * Test 3: An expired JWT is rejected with 401 Unauthorized.
     */
    @Test
    void expiredJwtIsRejected() throws Exception {
        String expiredToken = buildJwt("alice", TENANT_ID,
                Date.from(Instant.now().minusSeconds(3600))); // expired 1 hour ago

        mockMvc.perform(post("/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + expiredToken)
                        .content("""
                                {
                                    "sql": "SELECT * FROM jira_issues LIMIT 1",
                                    "include_latest_data": false,
                                    "max_staleness_ms": 60000,
                                    "timeout_ms": 5000
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test 4: A valid JWT for alice returns 200 OK.
     */
    @Test
    void validJwtForAliceReturns200() throws Exception {
        String validToken = buildJwt("alice", TENANT_ID,
                Date.from(Instant.now().plusSeconds(3600))); // expires in 1 hour

        mockMvc.perform(post("/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + validToken)
                        .content("""
                                {
                                    "sql": "SELECT issue_key, project_key FROM jira_issues LIMIT 1",
                                    "include_latest_data": false,
                                    "max_staleness_ms": 60000,
                                    "timeout_ms": 5000
                                }
                                """))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildJwt(String subject, String tenantId, Date expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("tenant_id", tenantId)
                .expirationTime(expiry)
                .issueTime(new Date())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(mockRsaKey.getKeyID())
                        .build(),
                claims
        );
        jwt.sign(new RSASSASigner(mockRsaKey));
        return jwt.serialize();
    }
}
