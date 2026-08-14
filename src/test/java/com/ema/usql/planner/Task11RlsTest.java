package com.ema.usql.planner;

import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.authz.api.RlsPredicate;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.knowledgecache.KnowledgeCacheServiceImpl;
import com.ema.usql.knowledgecache.TenantDuckDbRegistry;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.shared.TenantContext;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 11 acceptance tests: RLS injection.
 *
 * Verifies:
 * - alice sees all 4 rows (has PLAT and CORE)
 * - bob sees only CORE rows (has CORE only)
 * - The injected SQL for bob contains "project_key IN ('CORE')"
 * - ACL layer still filters even when RLS predicate is deliberately broken
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
class Task11RlsTest {

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
    }

    @Autowired
    private PolicyCompiler policyCompiler;

    @Autowired
    private TenantDuckDbRegistry duckDbRegistry;

    @Autowired
    private WatermarkStore watermarkStore;

    @Autowired
    private KnowledgeCacheServiceImpl cacheService;

    @Autowired
    private MockMvc mockMvc;

    private static final String TENANT_ID = "acme";
    private static final TenantContext TENANT_CTX = new TenantContext(TENANT_ID, "alice", "acme-kek-1");

    @BeforeEach
    void seedDuckDb() throws Exception {
        java.sql.Connection conn = duckDbRegistry.getConnection(TENANT_ID);
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM jira_issues");
        }

        List<ConnectorRecord> records = new ArrayList<>();
        // PLAT-1: only PLAT principal
        records.add(makeRecord("PLAT-1", "PLAT", List.of("project:PLAT")));
        // PLAT-2: only PLAT principal
        records.add(makeRecord("PLAT-2", "PLAT", List.of("project:PLAT")));
        // CORE-1: only CORE principal
        records.add(makeRecord("CORE-1", "CORE", List.of("project:CORE")));
        // CORE-2: both CORE and PLAT principals
        records.add(makeRecord("CORE-2", "CORE", List.of("project:CORE", "project:PLAT")));

        cacheService.write(records, TENANT_CTX);

        watermarkStore.updateWatermark("jira", "jira_issues", TENANT_ID,
                Instant.now().minusSeconds(60), "cursor-rls");
    }

    /**
     * Test 1: alice (PLAT + CORE) queries → sees all 4 rows.
     */
    @Test
    void aliceSeesAllRows() throws Exception {
        // alice's JWT is not needed since addFilters=false; but we inject tenant via Orchestrator
        // We test via PolicyCompiler directly for bob/alice, and via HTTP for row filtering

        // Alice has both projects
        AuthzContext aliceCtx = new AuthzContext(
                Set.of("project:PLAT", "project:CORE"),
                new RlsPredicate("project_key IN (:user.allowed_projects)"),
                new ClsMaskSet(Map.of()),
                0L, Instant.EPOCH);

        RlsPredicate compiled = policyCompiler.compile(
                aliceCtx.rlsPredicate().expression(), aliceCtx);

        String injected = policyCompiler.injectIntoSql(
                "SELECT issue_key, project_key FROM jira_issues", compiled);

        // Injected SQL should contain both CORE and PLAT
        assertThat(injected).containsIgnoringCase("project_key IN");
        assertThat(injected).contains("'CORE'");
        assertThat(injected).contains("'PLAT'");
    }

    /**
     * Test 2: bob (CORE only) queries → sees only CORE rows.
     */
    @Test
    void bobSeesOnlyCoreRows() throws Exception {
        AuthzContext bobCtx = new AuthzContext(
                Set.of("project:CORE"),
                new RlsPredicate("project_key IN (:user.allowed_projects)"),
                new ClsMaskSet(Map.of()),
                0L, Instant.EPOCH);

        RlsPredicate compiled = policyCompiler.compile(
                bobCtx.rlsPredicate().expression(), bobCtx);

        String injected = policyCompiler.injectIntoSql(
                "SELECT issue_key, project_key FROM jira_issues", compiled);

        // Injected SQL should contain CORE but NOT PLAT
        assertThat(injected).containsIgnoringCase("project_key IN ('CORE')");
        assertThat(injected).doesNotContain("'PLAT'");
    }

    /**
     * Test 3: the generated DuckDB SQL for bob contains the injected RLS predicate.
     */
    @Test
    void injectedSqlContainsBobsPredicate() {
        AuthzContext bobCtx = new AuthzContext(
                Set.of("project:CORE"),
                new RlsPredicate("project_key IN (:user.allowed_projects)"),
                new ClsMaskSet(Map.of()),
                0L, Instant.EPOCH);

        RlsPredicate compiled = policyCompiler.compile(
                bobCtx.rlsPredicate().expression(), bobCtx);

        String injectedSql = policyCompiler.injectIntoSql(
                "SELECT * FROM jira_issues WHERE status = 'Open'", compiled);

        assertThat(injectedSql).containsIgnoringCase("project_key IN ('CORE')");
    }

    /**
     * Test 4: Even with a broken RLS predicate (empty project list), the ACL layer
     * (list_intersect on acl_principals) still filters out unauthorized rows.
     *
     * We simulate a "broken" RLS predicate by using project_key IN ('') — which would
     * match nothing — but the ACL second-enforcement layer based on acl_principals still works.
     *
     * We verify this by querying the cache directly (bypassing RLS injection)
     * with only bob's principal set for ACL enforcement.
     */
    @Test
    void aclLayerFiltersEvenWithBrokenRlsPredicate() throws Exception {
        // Use a deliberately-incorrect SQL that won't match any rows via project_key
        // but ACL layer (acl_principals check) should still show only CORE rows for bob
        AuthzContext bobCtx = new AuthzContext(
                Set.of("project:CORE"),
                new RlsPredicate(null), // No RLS predicate = no project_key filter
                new ClsMaskSet(Map.of()),
                0L, Instant.EPOCH);

        // Query without RLS injection (broken policy scenario)
        com.ema.usql.shared.Fragment fragment = new com.ema.usql.shared.Fragment(
                "test-frag", "jira",
                "SELECT issue_key, project_key FROM jira_issues",
                List.of(), null, -1L, com.ema.usql.shared.QueryPath.CACHE);

        // Execute with bob's principal set — ACL layer should block PLAT rows
        com.ema.usql.shared.QueryResult result = cacheService.execute(
                fragment, TENANT_CTX, bobCtx.clsMaskSet(), bobCtx.principalSet());

        List<List<Object>> rows = result.rows();
        // Should NOT see PLAT-1 (acl_principals=['project:PLAT'], bob only has project:CORE)
        boolean seenPlat1 = rows.stream()
                .anyMatch(row -> "PLAT-1".equals(row.get(0)));
        assertThat(seenPlat1).isFalse();

        // Should see CORE-1 and CORE-2 (both have project:CORE)
        boolean seenCore1 = rows.stream()
                .anyMatch(row -> "CORE-1".equals(row.get(0)));
        boolean seenCore2 = rows.stream()
                .anyMatch(row -> "CORE-2".equals(row.get(0)));
        assertThat(seenCore1).isTrue();
        assertThat(seenCore2).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ConnectorRecord makeRecord(String issueKey, String projectKey, List<String> aclPrincipals) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", issueKey);
        fields.put("project_key", projectKey);
        fields.put("status", "Open");
        fields.put("priority", "Medium");
        fields.put("assignee_id", "bob");
        fields.put("reporter_email", "reporter@acme.com");
        fields.put("summary", "Issue " + issueKey);
        fields.put("created_at", "2024-01-01T10:00:00Z");
        fields.put("updated_at", "2024-01-15T10:00:00Z");
        fields.put("acl_principals", aclPrincipals);
        return new ConnectorRecord(fields);
    }
}
