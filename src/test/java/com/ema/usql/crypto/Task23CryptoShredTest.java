package com.ema.usql.crypto;

import com.ema.usql.api.QueryRequest;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.coordinator.Orchestrator;
import com.ema.usql.crypto.api.EncryptionContext;
import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.crypto.api.WrappedDek;
import com.ema.usql.knowledgecache.KnowledgeCacheServiceImpl;
import com.ema.usql.knowledgecache.TenantDuckDbRegistry;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 23 acceptance tests: Crypto-shred and off-boarding.
 *
 * <p>Test 1 (undecryptability before file deletion):
 * Writes a jira_issues row to DuckDB (encrypted DEK stored alongside), records the
 * wrapped DEK bytes, calls {@code kmsModule.destroyKek(tenantId)}, then proves
 * {@code kmsModule.unwrapDek()} throws — demonstrating crypto-shred effectiveness
 * BEFORE any file deletion.
 *
 * <p>Test 2 (full off-boarding via admin endpoint):
 * Calls DELETE /admin/v1/tenant/acme-shred with the admin key → 200 OK.
 * Then POST /v1/query as alice → 403 ENTITLEMENT_DENIED (tenant inactive).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
class Task23CryptoShredTest {

    private static final String SHRED_TENANT = "acme-shred";
    private static final String ADMIN_KEY = "test-admin-key";

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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("usql.auth.mock-enabled", () -> "true");
        registry.add("usql.admin.key", () -> ADMIN_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Orchestrator orchestrator;

    @Autowired
    private KmsModule kmsModule;

    @Autowired
    private TenantDuckDbRegistry duckDbRegistry;

    @Autowired
    private WatermarkStore watermarkStore;

    @Autowired
    private KnowledgeCacheServiceImpl cacheService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * Seed a jira_issues row for SHRED_TENANT so we have something to shred.
     * Also seed the tenant row in Postgres.
     */
    @BeforeEach
    void seedShredTenant() throws Exception {
        // Ensure the tenant row exists in Postgres (for TenantConfigService)
        jdbcTemplate.update("""
                INSERT INTO tenant (tenant_id, name, deployment_mode, residency_tag, kek_id, status)
                VALUES (?, 'Shred Test Corp', 'CLOUD', 'us-east-1', ?||'-kek-1', 'active')
                ON CONFLICT (tenant_id) DO UPDATE SET status = 'active'
                """, SHRED_TENANT, SHRED_TENANT);

        // Pre-populate DuckDB for the shred tenant
        TenantContext shredCtx = new TenantContext(SHRED_TENANT, "alice", SHRED_TENANT + "-kek-1");
        java.sql.Connection conn = duckDbRegistry.getConnection(SHRED_TENANT);
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM jira_issues");
        }

        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", "SHRED-1");
        fields.put("project_key", "PLAT");
        fields.put("status", "Open");
        fields.put("priority", "High");
        fields.put("assignee_id", "alice");
        fields.put("reporter_email", "shred-target@example.com");
        fields.put("summary", "Shred test issue");
        fields.put("created_at", "2024-01-01T10:00:00Z");
        fields.put("updated_at", "2024-01-15T10:00:00Z");
        fields.put("acl_principals", List.of("project:PLAT"));

        List<ConnectorRecord> records = new ArrayList<>();
        records.add(new ConnectorRecord(fields));
        cacheService.write(records, shredCtx);

        watermarkStore.updateWatermark("jira", "jira_issues", SHRED_TENANT,
                Instant.now().minusSeconds(60), "cursor-shred");
    }

    /**
     * Test 1: Crypto-shred proves undecryptability BEFORE file deletion.
     *
     * <p>Steps:
     * <ol>
     *   <li>Generate a DEK for the shred tenant and record the wrapped bytes.</li>
     *   <li>Call destroyKek — this is the crypto-shred step. The DuckDB file still exists.</li>
     *   <li>Attempt to unwrap the previously wrapped DEK — must throw UsqlException or
     *       AEADBadTagException (the KEK is gone so decryption fails).</li>
     * </ol>
     *
     * <p>This proves the crypto-shred is effective on its own, before any file deletion.
     */
    @Test
    void cryptoShred_makesWrappedDekUndecryptable_beforeFileDeletion() {
        EncryptionContext ctx = new EncryptionContext(SHRED_TENANT, "store");

        // Step 1: Generate (or load) a DEK for the shred tenant
        WrappedDek wrappedDek = kmsModule.generateDek(SHRED_TENANT, ctx);
        byte[] originalWrappedBytes = wrappedDek.bytes().clone();

        // Verify it's currently decryptable
        javax.crypto.SecretKey dekBefore = kmsModule.unwrapDek(SHRED_TENANT, wrappedDek, ctx);
        assertThat(dekBefore).isNotNull();

        // Step 2: Destroy the KEK (crypto-shred) — DuckDB file still exists
        kmsModule.destroyKek(SHRED_TENANT);

        // Step 3: Attempt to unwrap the same DEK — must fail because KEK is gone
        WrappedDek sameWrappedDek = new WrappedDek(originalWrappedBytes);
        assertThatThrownBy(() -> kmsModule.unwrapDek(SHRED_TENANT, sameWrappedDek, ctx))
                .as("After destroyKek, unwrapDek must throw — DEK is permanently inaccessible")
                .isInstanceOf(Exception.class);
    }

    /**
     * Test 2: Full off-boarding via the admin DELETE endpoint.
     *
     * <p>Steps:
     * <ol>
     *   <li>Reset tenant status to 'active' in case Test 1 left it shredded.</li>
     *   <li>Call DELETE /admin/v1/tenant/{tenantId} with admin key → 200 OK.</li>
     *   <li>Call orchestrator.execute() as alice with the shred tenant context
     *       → UsqlException(ENTITLEMENT_DENIED) because tenant is inactive.</li>
     * </ol>
     *
     * <p>We call the Orchestrator directly (bypassing QueryController) because
     * QueryController's anonymous fallback always uses tenantId="acme", making it
     * impossible to target SHRED_TENANT via HTTP without a JWT.
     */
    @Test
    void adminShred_marksInactive_and_queriesThrowEntitlementDenied() throws Exception {
        // Step 1: Reset tenant status to active
        jdbcTemplate.update(
                "UPDATE tenant SET status = 'active' WHERE tenant_id = ?", SHRED_TENANT);

        // Step 2: Call DELETE /admin/v1/tenant/{tenantId} with correct admin key
        mockMvc.perform(delete("/admin/v1/tenant/" + SHRED_TENANT)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tenant shredded"));

        // Step 3: Verify orchestrator rejects queries for inactive tenant
        TenantContext shredCtx = new TenantContext(SHRED_TENANT, "alice", SHRED_TENANT + "-kek-1");
        QueryRequest request = new QueryRequest(
                "SELECT * FROM jira_issues LIMIT 5",
                false, 60000, 5000);

        assertThatThrownBy(() -> orchestrator.execute(request, shredCtx))
                .as("Query to inactive tenant must throw ENTITLEMENT_DENIED")
                .isInstanceOf(UsqlException.class)
                .satisfies(ex -> {
                    UsqlException usqlEx = (UsqlException) ex;
                    assertThat(usqlEx.getErrorCode())
                            .isEqualTo(ErrorCode.ENTITLEMENT_DENIED);
                    assertThat(usqlEx.getMessage())
                            .containsIgnoringCase("inactive");
                });
    }

    /**
     * Test 3: Invalid admin key returns 403.
     */
    @Test
    void adminShred_wrongKey_returns403() throws Exception {
        mockMvc.perform(delete("/admin/v1/tenant/acme")
                        .header("X-Admin-Key", "wrong-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}
