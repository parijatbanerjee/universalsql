package com.ema.usql.livequery;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 acceptance tests: Live Query Engine.
 *
 * Test 1: include_latest_data=true → sources[0].path = "LIVE", freshness_ms = 0
 * Test 2: Slow source (2500ms delay) + timeout_ms=500 → partial=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class Task16LiveQueryTest {

    private static final int JIRA_PORT = 18093;

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
        registry.add("usql.connectors.github.url", () -> "http://localhost:18094");
    }

    @Autowired
    Orchestrator orchestrator;

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
    void setupStubs() {
        jiraServer.resetAll();
        stubNormalJira();
    }

    private void stubNormalJira() {
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "total": 2,
                                  "issues": [
                                    {"key": "PLAT-1", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "High"}, "assignee": {"accountId": "alice"}, "summary": "Live Issue 1", "created": "2024-01-01T10:00:00Z", "updated": "2024-01-15T10:00:00Z"}},
                                    {"key": "PLAT-2", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "Medium"}, "assignee": {"accountId": "alice"}, "summary": "Live Issue 2", "created": "2024-01-02T10:00:00Z", "updated": "2024-01-16T10:00:00Z"}}
                                  ]
                                }
                                """)));
    }

    // -------------------------------------------------------------------------
    // Test 1: include_latest_data=true → path=LIVE, freshness_ms=0
    // -------------------------------------------------------------------------

    @Test
    void liveQuery_returnsLivePath_andFreshnessMsZero() {
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        QueryRequest request = new QueryRequest(
                "SELECT * FROM jira_issues",
                true,   // includeLatestData = true → LIVE path
                0,
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response).isNotNull();
        assertThat(response.metadata()).isNotNull();
        assertThat(response.metadata().sources()).isNotEmpty();

        // The source path must be LIVE
        String path = response.metadata().sources().get(0).path();
        assertThat(path).isEqualTo("LIVE");

        // freshness_ms must be 0 for live results
        long freshnessMs = response.metadata().sources().get(0).freshnessMs();
        assertThat(freshnessMs).isEqualTo(0L);

        // Must have returned rows (from WireMock)
        assertThat(response.rows()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 2: Slow source + short timeout → partial=true
    // -------------------------------------------------------------------------

    @Test
    void liveQuery_slowSource_partialResult() {
        // Override stub with 2500ms delay
        jiraServer.resetAll();
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(2500)
                        .withBody("""
                                {"total": 0, "issues": []}
                                """)));

        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        QueryRequest request = new QueryRequest(
                "SELECT * FROM jira_issues",
                true,   // includeLatestData = true → LIVE path
                0,
                500     // very short timeout → should expire before 2500ms response
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response).isNotNull();
        assertThat(response.metadata()).isNotNull();
        // partial=true because the live call timed out
        assertThat(response.metadata().partial()).isTrue();
    }
}
