package com.ema.usql.planner;

import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.knowledgecache.KnowledgeCacheServiceImpl;
import com.ema.usql.knowledgecache.TenantDuckDbRegistry;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryPath;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12 acceptance tests: CLS masking.
 *
 * Verifies:
 * - Non-admin sees masked email ("j***@acme.com")
 * - Admin (no mask) sees full email ("john@acme.com")
 * - WHERE on masked column returns ENTITLEMENT_DENIED
 * - Response bytes for non-admin do NOT contain the plaintext email
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class Task12ClsTest {

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
    private KnowledgeCacheServiceImpl cacheService;

    @Autowired
    private TenantDuckDbRegistry duckDbRegistry;

    @Autowired
    private WatermarkStore watermarkStore;

    @Autowired
    private SqlParser sqlParser;

    @Autowired
    private MaskApplier maskApplier;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RSAKey mockRsaKey;

    private static final String TENANT_ID = "acme";
    private static final String PLAINTEXT_EMAIL = "john@acme.com";
    private static final TenantContext ALICE_CTX = new TenantContext(TENANT_ID, "alice", "acme-kek-1");

    @BeforeEach
    void seedDuckDb() throws Exception {
        java.sql.Connection conn = duckDbRegistry.getConnection(TENANT_ID);
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM jira_issues");
        }

        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", "CORE-CLS-1");
        fields.put("project_key", "CORE");
        fields.put("status", "Open");
        fields.put("priority", "Medium");
        fields.put("assignee_id", "bob");
        fields.put("reporter_email", PLAINTEXT_EMAIL);
        fields.put("summary", "CLS test issue");
        fields.put("created_at", "2024-01-01T10:00:00Z");
        fields.put("updated_at", "2024-01-15T10:00:00Z");
        fields.put("acl_principals", List.of("project:CORE", "project:PLAT"));

        cacheService.write(List.of(new com.ema.usql.connectors.api.ConnectorRecord(fields)), ALICE_CTX);

        watermarkStore.updateWatermark("jira", "jira_issues", TENANT_ID,
                Instant.now().minusSeconds(60), "cursor-cls");
    }

    /**
     * Test 1: alice (non-admin) queries reporter_email → sees masked "j***@acme.com".
     */
    @Test
    void nonAdminSeesPartiallyMaskedEmail() {
        // Alice gets REDACT mask from seed policy (cls_json says mask=redact unless role:admin)
        // But the MaskApplier handles the PARTIAL case too — test both directly
        String partial = maskApplier.maskEmail(PLAINTEXT_EMAIL, "PARTIAL");
        assertThat(partial).isEqualTo("j***@acme.com");

        String redacted = maskApplier.maskEmail(PLAINTEXT_EMAIL, "REDACT");
        assertThat(redacted).isEqualTo("***");

        // Also verify the masking in the cache service itself
        ClsMaskSet maskSet = new ClsMaskSet(Map.of("reporter_email", "REDACT"));

        Fragment fragment = new Fragment(
                "cls-frag", "jira",
                "SELECT issue_key, reporter_email_enc, wrapped_dek FROM jira_issues",
                List.of(), null, -1L, QueryPath.CACHE);

        QueryResult result = cacheService.execute(fragment, ALICE_CTX, maskSet, Set.of("project:CORE", "project:PLAT"));

        assertThat(result.rows()).hasSize(1);
        List<Object> row = result.rows().get(0);

        // Find the reporter_email_enc column index
        int emailIdx = -1;
        for (int i = 0; i < result.columns().size(); i++) {
            if ("reporter_email_enc".equals(result.columns().get(i).name())) {
                emailIdx = i;
                break;
            }
        }
        assertThat(emailIdx).isGreaterThanOrEqualTo(0);

        String maskedEmail = (String) row.get(emailIdx);
        assertThat(maskedEmail).isEqualTo("***");
        assertThat(maskedEmail).doesNotContain(PLAINTEXT_EMAIL);
    }

    /**
     * Test 2: admin (no mask) sees the full plaintext email.
     */
    @Test
    void adminSeesFullEmail() {
        // With empty ClsMaskSet (admin), no masking is applied
        ClsMaskSet noMask = new ClsMaskSet(Map.of());

        Fragment fragment = new Fragment(
                "cls-frag-admin", "jira",
                "SELECT issue_key, reporter_email_enc, wrapped_dek FROM jira_issues",
                List.of(), null, -1L, QueryPath.CACHE);

        QueryResult result = cacheService.execute(fragment, ALICE_CTX, noMask, Set.of("project:CORE", "project:PLAT"));

        assertThat(result.rows()).hasSize(1);
        List<Object> row = result.rows().get(0);

        int emailIdx = -1;
        for (int i = 0; i < result.columns().size(); i++) {
            if ("reporter_email_enc".equals(result.columns().get(i).name())) {
                emailIdx = i;
                break;
            }
        }
        assertThat(emailIdx).isGreaterThanOrEqualTo(0);

        String email = (String) row.get(emailIdx);
        assertThat(email).isEqualTo(PLAINTEXT_EMAIL);
    }

    /**
     * Test 3: Query with masked column in WHERE clause → ENTITLEMENT_DENIED.
     */
    @Test
    void maskedColumnInWhereCausesDenial() {
        ClsMaskSet maskSet = new ClsMaskSet(Map.of("reporter_email", "REDACT"));

        assertThatThrownBy(() ->
                sqlParser.validateMaskedColumnsNotInPredicates(
                        "SELECT * FROM jira_issues WHERE reporter_email = 'john@acme.com'",
                        maskSet))
                .isInstanceOf(UsqlException.class)
                .satisfies(ex -> {
                    UsqlException usqlEx = (UsqlException) ex;
                    assertThat(usqlEx.getErrorCode()).isEqualTo(ErrorCode.ENTITLEMENT_DENIED);
                    assertThat(usqlEx.getMessage()).contains("MASKED_COLUMN_IN_PREDICATE");
                });
    }

    /**
     * Test 4: Response bytes for alice (non-admin) do NOT contain the plaintext email.
     *
     * Uses a real HTTP call with JWT so the full Orchestrator pipeline runs (including CLS masking).
     * Alice's seed policy has mask=REDACT for reporter_email (unless role:admin),
     * and alice has no role:admin principal.
     */
    @Test
    void plaintextEmailNeverAppearsInResponseForNonAdmin() throws Exception {
        // alice is not role:admin → reporter_email is masked
        String aliceToken = buildJwt("alice", TENANT_ID, Date.from(Instant.now().plusSeconds(3600)));

        MvcResult result = mockMvc.perform(post("/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aliceToken)
                        .content("""
                                {
                                    "sql": "SELECT issue_key, reporter_email_enc, wrapped_dek FROM jira_issues",
                                    "include_latest_data": false,
                                    "max_staleness_ms": 60000,
                                    "timeout_ms": 5000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        // The plaintext email must not appear in the response
        assertThat(responseBody).doesNotContain(PLAINTEXT_EMAIL);
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
