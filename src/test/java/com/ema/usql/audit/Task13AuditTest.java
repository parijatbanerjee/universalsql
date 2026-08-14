package com.ema.usql.audit;

import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.knowledgecache.KnowledgeCacheServiceImpl;
import com.ema.usql.knowledgecache.TenantDuckDbRegistry;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.shared.TenantContext;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 13 acceptance tests: Audit service.
 *
 * Verifies:
 * - A successful query produces an audit_event row with decision=ALLOW and matching trace_id
 * - A denied query (masked column in WHERE) produces an audit_event row with decision=DENY
 * - No audit row contains the test email address or other sensitive row data
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class Task13AuditTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantDuckDbRegistry duckDbRegistry;

    @Autowired
    private WatermarkStore watermarkStore;

    @Autowired
    private KnowledgeCacheServiceImpl cacheService;

    @Autowired
    private RSAKey mockRsaKey;

    private static final String TENANT_ID = "acme";
    private static final String TEST_EMAIL = "audit-test@acme.com";
    private static final TenantContext ALICE_CTX = new TenantContext(TENANT_ID, "alice", "acme-kek-1");

    @BeforeEach
    void setUp() throws Exception {
        // Clean audit events and DuckDB
        jdbcTemplate.execute("DELETE FROM audit_event");

        java.sql.Connection conn = duckDbRegistry.getConnection(TENANT_ID);
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM jira_issues");
        }

        // Seed one Jira issue
        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", "AUDIT-1");
        fields.put("project_key", "CORE");
        fields.put("status", "Open");
        fields.put("priority", "High");
        fields.put("assignee_id", "alice");
        fields.put("reporter_email", TEST_EMAIL);
        fields.put("summary", "Audit test issue");
        fields.put("created_at", "2024-01-01T10:00:00Z");
        fields.put("updated_at", "2024-01-15T10:00:00Z");
        fields.put("acl_principals", List.of("project:CORE", "project:PLAT"));

        cacheService.write(List.of(new ConnectorRecord(fields)), ALICE_CTX);

        watermarkStore.updateWatermark("jira", "jira_issues", TENANT_ID,
                Instant.now().minusSeconds(60), "cursor-audit");
    }

    /**
     * Test 1: Execute a valid query → audit_event row exists with decision=ALLOW and matching trace_id.
     */
    @Test
    void successfulQueryProducesAllowAuditEvent() throws Exception {
        String aliceToken = buildJwt("alice", TENANT_ID, Date.from(Instant.now().plusSeconds(3600)));

        MvcResult result = mockMvc.perform(post("/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aliceToken)
                        .content("""
                                {
                                    "sql": "SELECT issue_key, project_key FROM jira_issues LIMIT 5",
                                    "include_latest_data": false,
                                    "max_staleness_ms": 60000,
                                    "timeout_ms": 5000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.trace_id", notNullValue()))
                .andReturn();

        // Extract trace_id from response
        String responseBody = result.getResponse().getContentAsString();
        String traceId = extractTraceId(responseBody);
        assertThat(traceId).isNotNull();

        // Verify audit event exists with matching trace_id and decision=ALLOW
        List<Map<String, Object>> auditRows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_event WHERE trace_id = ? AND tenant_id = ?",
                traceId, TENANT_ID);

        assertThat(auditRows).hasSize(1);
        Map<String, Object> auditRow = auditRows.get(0);
        assertThat(auditRow.get("decision")).isEqualTo("ALLOW");
        assertThat(auditRow.get("action")).isEqualTo("QUERY");
        assertThat(auditRow.get("user_id")).isEqualTo("alice");
        assertThat(auditRow.get("sql_hash")).isNotNull();
    }

    /**
     * Test 2: Execute a query that triggers ENTITLEMENT_DENIED (masked column in WHERE)
     * → audit_event row with decision=DENY, reason=MASKED_COLUMN_IN_PREDICATE.
     */
    @Test
    void deniedQueryProducesDenyAuditEvent() throws Exception {
        String aliceToken = buildJwt("alice", TENANT_ID, Date.from(Instant.now().plusSeconds(3600)));

        // reporter_email is in the WHERE clause — alice is non-admin so it's masked
        mockMvc.perform(post("/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aliceToken)
                        .content("""
                                {
                                    "sql": "SELECT * FROM jira_issues WHERE reporter_email = 'x@y.com'",
                                    "include_latest_data": false,
                                    "max_staleness_ms": 60000,
                                    "timeout_ms": 5000
                                }
                                """))
                .andExpect(status().isForbidden());

        // Verify audit event with decision=DENY
        List<Map<String, Object>> auditRows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_event WHERE tenant_id = ? AND decision = 'DENY' ORDER BY ts DESC LIMIT 1",
                TENANT_ID);

        assertThat(auditRows).hasSize(1);
        Map<String, Object> auditRow = auditRows.get(0);
        assertThat(auditRow.get("decision")).isEqualTo("DENY");
        assertThat(auditRow.get("reason").toString()).contains("MASKED_COLUMN_IN_PREDICATE");
        assertThat(auditRow.get("user_id")).isEqualTo("alice");
    }

    /**
     * Test 3: Query all audit_event rows — none contain the test email address.
     */
    @Test
    void auditRowsNeverContainEmailOrSensitiveData() throws Exception {
        // Execute a successful query to generate an audit event
        String aliceToken = buildJwt("alice", TENANT_ID, Date.from(Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aliceToken)
                        .content("""
                                {
                                    "sql": "SELECT issue_key FROM jira_issues LIMIT 1",
                                    "include_latest_data": false,
                                    "max_staleness_ms": 60000,
                                    "timeout_ms": 5000
                                }
                                """))
                .andExpect(status().isOk());

        // Query all audit_event rows and check for email
        List<Map<String, Object>> allAuditRows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_event WHERE tenant_id = ?", TENANT_ID);

        assertThat(allAuditRows).isNotEmpty();

        for (Map<String, Object> row : allAuditRows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() != null) {
                    String valueStr = entry.getValue().toString();
                    assertThat(valueStr)
                            .as("Audit column '%s' should not contain plaintext email", entry.getKey())
                            .doesNotContain(TEST_EMAIL);
                }
            }
        }
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

    private String extractTraceId(String responseBody) {
        // Simple JSON extraction without a JSON library
        int idx = responseBody.indexOf("\"trace_id\":");
        if (idx < 0) return null;
        int start = responseBody.indexOf('"', idx + 11) + 1;
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }
}
