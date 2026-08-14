package com.ema.usql.planner;

import com.ema.usql.api.QueryRequest;
import com.ema.usql.api.QueryResponse;
import com.ema.usql.coordinator.Orchestrator;
import com.ema.usql.shared.TenantContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 17 integration tests: PathSelector wired into the Orchestrator.
 *
 * Test 1: include_latest_data=false → sources[0].path = "CACHE"
 * Test 2: include_latest_data=true → sources[0].path = "LIVE" (watermark stale)
 * Test 3: Forced stale ACL → sources[0].path = "LIVE" even with include_latest_data=false
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class Task17IntegrationTest {

    private static final int JIRA_PORT = 18095;
    private static WireMockServer jiraServer;

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
        registry.add("usql.connectors.jira.url", () -> "http://localhost:" + JIRA_PORT);
        registry.add("usql.connectors.github.url", () -> "http://localhost:18096");
    }

    @Autowired
    Orchestrator orchestrator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startWireMock() {
        jiraServer = new WireMockServer(WireMockConfiguration.options().port(JIRA_PORT));
        jiraServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        jiraServer.stop();
    }

    @BeforeEach
    void setup() {
        jiraServer.resetAll();
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "total": 1,
                                  "issues": [
                                    {"key": "PLAT-17", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "High"}, "assignee": {"accountId": "alice"}, "summary": "Path selector test", "created": "2024-01-01T10:00:00Z", "updated": "2024-01-15T10:00:00Z"}}
                                  ]
                                }
                                """)));

        // Restore ACL sync time to "now" (fresh) before each test
        jdbcTemplate.update("""
                UPDATE resource_acl SET acl_synced_at = NOW()
                WHERE tenant_id = 'acme'
                """);
    }

    // -------------------------------------------------------------------------
    // Test 1: include_latest_data=false → CACHE
    // -------------------------------------------------------------------------

    @Test
    void cacheQuery_returnsCachePath() {
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");
        QueryRequest request = new QueryRequest(
                "SELECT * FROM jira_issues",
                false,  // include_latest_data = false → CACHE
                0,
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response.metadata().sources()).isNotEmpty();
        String path = response.metadata().sources().get(0).path();
        assertThat(path).isEqualTo("CACHE");
    }

    // -------------------------------------------------------------------------
    // Test 2: include_latest_data=true → LIVE (watermark is old enough to be stale)
    // -------------------------------------------------------------------------

    @Test
    void liveQuery_returnsLivePath() {
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");
        QueryRequest request = new QueryRequest(
                "SELECT * FROM jira_issues",
                true,   // include_latest_data = true → LIVE (if watermark stale)
                0,      // maxStalenessMs=0 → any watermark age makes it "stale"
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response.metadata().sources()).isNotEmpty();
        String path = response.metadata().sources().get(0).path();
        // Watermark is null (no data in cache) → considered stale → LIVE path
        assertThat(path).isEqualTo("LIVE");
    }

    // -------------------------------------------------------------------------
    // Test 3: Forced stale ACL → LIVE even with include_latest_data=false
    // -------------------------------------------------------------------------

    @Test
    void staleAcl_forcesLivePath_evenWithoutLatestDataFlag() {
        // Force the ACL sync time to 10 minutes ago (> 5-minute ACL_MAX_AGE)
        jdbcTemplate.update("""
                UPDATE resource_acl SET acl_synced_at = NOW() - INTERVAL '10 minutes'
                WHERE tenant_id = 'acme'
                """);

        // Insert at least one ACL row for acme/alice to ensure the update takes effect
        jdbcTemplate.update("""
                INSERT INTO resource_acl (tenant_id, source, resource_id, principal_id, acl_version, acl_synced_at)
                VALUES ('acme', 'jira', 'PLAT-1', 'project:PLAT', 1, NOW() - INTERVAL '10 minutes')
                ON CONFLICT (tenant_id, source, resource_id, principal_id)
                DO UPDATE SET acl_synced_at = NOW() - INTERVAL '10 minutes'
                """);

        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");
        QueryRequest request = new QueryRequest(
                "SELECT * FROM jira_issues",
                false,  // include_latest_data = false — but stale ACL overrides
                0,
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response.metadata().sources()).isNotEmpty();
        String path = response.metadata().sources().get(0).path();
        // PathSelector step 1: stale ACL → LIVE regardless of includeLatestData
        assertThat(path).isEqualTo("LIVE");
    }
}
