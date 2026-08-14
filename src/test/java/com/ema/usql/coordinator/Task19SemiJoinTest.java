package com.ema.usql.coordinator;

import com.ema.usql.api.QueryRequest;
import com.ema.usql.api.QueryResponse;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.knowledgecache.KnowledgeCacheServiceImpl;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.planner.JoinStrategySelector;
import com.ema.usql.shared.JoinStrategy;
import com.ema.usql.shared.TenantContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 19 acceptance tests: Semi-Join Reduction
 *
 * Test 1: Cross-source join query → join_strategy: SEMI_JOIN_REDUCTION
 * Test 2: Force large side A (101 issues) → join_strategy: DUCKDB_HASH_JOIN
 * Test 3: Verify GitHub WireMock request had issue_keys filter (not full fetch)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class Task19SemiJoinTest {

    private static final int JIRA_PORT = 18099;
    private static final int GITHUB_PORT = 18100;

    private static WireMockServer jiraServer;
    private static WireMockServer githubServer;

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
        registry.add("usql.connectors.github.url", () -> "http://localhost:" + GITHUB_PORT);
    }

    @Autowired
    Orchestrator orchestrator;

    @Autowired
    JoinStrategySelector joinStrategySelector;

    @Autowired
    KnowledgeCacheServiceImpl knowledgeCacheService;

    @Autowired
    WatermarkStore watermarkStore;

    @BeforeAll
    static void startWireMock() {
        jiraServer = new WireMockServer(WireMockConfiguration.options().port(JIRA_PORT));
        jiraServer.start();

        githubServer = new WireMockServer(WireMockConfiguration.options().port(GITHUB_PORT));
        githubServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        jiraServer.stop();
        githubServer.stop();
    }

    @BeforeEach
    void setup() {
        jiraServer.resetAll();
        githubServer.resetAll();

        // Jira: returns 2 issues (PLAT-1, PLAT-2) for PLAT project
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "total": 2,
                                  "issues": [
                                    {"key": "PLAT-1", "fields": {
                                      "project": {"key": "PLAT"},
                                      "status": {"name": "Open"},
                                      "priority": {"name": "High"},
                                      "assignee": {"accountId": "alice"},
                                      "summary": "Fix login bug",
                                      "created": "2024-01-01T10:00:00Z",
                                      "updated": "2024-01-15T10:00:00Z"
                                    }},
                                    {"key": "PLAT-2", "fields": {
                                      "project": {"key": "PLAT"},
                                      "status": {"name": "In Progress"},
                                      "priority": {"name": "Medium"},
                                      "assignee": {"accountId": "bob"},
                                      "summary": "Add OAuth support",
                                      "created": "2024-01-02T10:00:00Z",
                                      "updated": "2024-01-16T10:00:00Z"
                                    }}
                                  ]
                                }
                                """)));

        // GitHub: returns PRs matching issue_keys filter (PLAT-1 and PLAT-2)
        githubServer.stubFor(get(urlPathMatching("/repos/acme/issues/pulls"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "number": 101,
                                    "title": "PLAT-1: fix login",
                                    "state": "open",
                                    "user": {"login": "alice"},
                                    "created_at": "2024-01-10T10:00:00Z",
                                    "updated_at": "2024-01-15T10:00:00Z"
                                  },
                                  {
                                    "number": 102,
                                    "title": "PLAT-2: add OAuth",
                                    "state": "open",
                                    "user": {"login": "bob"},
                                    "created_at": "2024-01-11T10:00:00Z",
                                    "updated_at": "2024-01-16T10:00:00Z"
                                  }
                                ]
                                """)));
    }

    // -------------------------------------------------------------------------
    // Test 1: Cross-source join → SEMI_JOIN_REDUCTION
    // -------------------------------------------------------------------------

    @Test
    void crossSourceJoin_reportsSemiJoinReduction() {
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        QueryRequest request = new QueryRequest(
                "SELECT i.issue_key, i.status, p.title " +
                "FROM jira_issues i JOIN github_prs p ON p.linked_issue_key = i.issue_key " +
                "WHERE i.project_key = 'PLAT'",
                true,
                0,
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response).isNotNull();
        assertThat(response.metadata()).isNotNull();

        // join_strategy must be SEMI_JOIN_REDUCTION (side A has 2 rows < 100 ceiling)
        assertThat(response.metadata().joinStrategy())
                .as("join_strategy should be SEMI_JOIN_REDUCTION")
                .isEqualTo("SEMI_JOIN_REDUCTION");
    }

    // -------------------------------------------------------------------------
    // Test 2: GitHub WireMock received a FILTERED request (issue_keys param)
    // -------------------------------------------------------------------------

    @Test
    void crossSourceJoin_githubRequestHasIssueKeysFilter() throws Exception {
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        QueryRequest request = new QueryRequest(
                "SELECT i.issue_key, i.status, p.title " +
                "FROM jira_issues i JOIN github_prs p ON p.linked_issue_key = i.issue_key " +
                "WHERE i.project_key = 'PLAT'",
                true,
                0,
                5000
        );

        orchestrator.execute(request, ctx);

        // Verify GitHub received at least one request
        List<LoggedRequest> githubRequests = githubServer.findAll(
                getRequestedFor(urlPathEqualTo("/repos/acme/issues/pulls")));

        assertThat(githubRequests)
                .as("GitHub should have received at least one request")
                .isNotEmpty();

        // At least one GitHub request should have the issue_keys filter
        boolean anyWithIssueKeys = githubRequests.stream()
                .anyMatch(req -> {
                    String url = req.getUrl();
                    // Check URL for issue_keys param (may be URL-encoded)
                    return url.contains("issue_keys=") || url.contains("issue_keys%3D");
                });

        // Also check the request query parameters directly
        boolean anyWithIssueKeysParam = githubRequests.stream()
                .anyMatch(req -> req.queryParameter("issue_keys").isPresent());

        assertThat(anyWithIssueKeys || anyWithIssueKeysParam)
                .as("At least one GitHub request should contain issue_keys parameter. " +
                    "Requests received: " + githubRequests.stream()
                        .map(r -> r.getUrl()).toList())
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 3: Result rows contain merged columns from both sources
    // -------------------------------------------------------------------------

    @Test
    void crossSourceJoin_responseContainsMergedColumns() {
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        QueryRequest request = new QueryRequest(
                "SELECT i.issue_key, i.status, p.title " +
                "FROM jira_issues i JOIN github_prs p ON p.linked_issue_key = i.issue_key " +
                "WHERE i.project_key = 'PLAT'",
                true,
                0,
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response.rows()).isNotEmpty();

        // Verify the response has columns from both tables
        List<String> columnNames = response.columns().stream()
                .map(col -> col.name())
                .toList();

        // Should have at least one column from jira_issues (e.g. issue_key or status)
        // and one from github_prs (e.g. title)
        boolean hasJiraCol = columnNames.stream()
                .anyMatch(c -> c.equals("issue_key") || c.equals("status") || c.equals("project_key"));
        boolean hasGithubCol = columnNames.stream()
                .anyMatch(c -> c.equals("title") || c.equals("pr_number") || c.equals("linked_issue_key"));

        assertThat(hasJiraCol)
                .as("Response should include Jira columns. Got: " + columnNames)
                .isTrue();
        assertThat(hasGithubCol)
                .as("Response should include GitHub columns. Got: " + columnNames)
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 4: Force large side A (101 issues) → DUCKDB_HASH_JOIN
    // -------------------------------------------------------------------------

    @Test
    void largeJiraSideA_fallsBackToDuckDbHashJoin() {
        // Override Jira stub to return 101 issues
        jiraServer.resetAll();
        StringBuilder jiraResponse = new StringBuilder("""
                {"total": 101, "issues": [
                """);
        for (int i = 1; i <= 101; i++) {
            if (i > 1) jiraResponse.append(",");
            jiraResponse.append(String.format("""
                    {"key": "PLAT-%d", "fields": {
                      "project": {"key": "PLAT"},
                      "status": {"name": "Open"},
                      "priority": {"name": "Medium"},
                      "assignee": {"accountId": "alice"},
                      "summary": "Issue %d",
                      "created": "2024-01-01T10:00:00Z",
                      "updated": "2024-01-15T10:00:00Z"
                    }}
                    """, i, i));
        }
        jiraResponse.append("]}");

        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jiraResponse.toString())));

        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        QueryRequest request = new QueryRequest(
                "SELECT i.issue_key, i.status, p.title " +
                "FROM jira_issues i JOIN github_prs p ON p.linked_issue_key = i.issue_key " +
                "WHERE i.project_key = 'PLAT'",
                true,
                0,
                5000
        );

        QueryResponse response = orchestrator.execute(request, ctx);

        assertThat(response).isNotNull();
        assertThat(response.metadata().joinStrategy())
                .as("With 101 Jira rows, should fall back to DUCKDB_HASH_JOIN")
                .isEqualTo("DUCKDB_HASH_JOIN");
    }

    // -------------------------------------------------------------------------
    // Test 5: JoinStrategySelector unit behaviour
    // -------------------------------------------------------------------------

    @Test
    void joinStrategySelector_semiJoinBelowCeiling() {
        // Any LogicalPlan — we just need to test the selector logic
        com.ema.usql.planner.LogicalPlan plan = new com.ema.usql.planner.LogicalPlan(
                List.of("*"), List.of("jira_issues", "github_prs"),
                "p.linked_issue_key = i.issue_key", "github_prs",
                null, null, null, Map.of("i", "jira_issues", "p", "github_prs")
        );

        assertThat(joinStrategySelector.select(plan, 99))
                .isEqualTo(JoinStrategy.SEMI_JOIN_REDUCTION);
        assertThat(joinStrategySelector.select(plan, 0))
                .isEqualTo(JoinStrategy.SEMI_JOIN_REDUCTION);
    }

    @Test
    void joinStrategySelector_hashJoinAtOrAboveCeiling() {
        com.ema.usql.planner.LogicalPlan plan = new com.ema.usql.planner.LogicalPlan(
                List.of("*"), List.of("jira_issues", "github_prs"),
                "p.linked_issue_key = i.issue_key", "github_prs",
                null, null, null, Map.of("i", "jira_issues", "p", "github_prs")
        );

        assertThat(joinStrategySelector.select(plan, 100))
                .isEqualTo(JoinStrategy.DUCKDB_HASH_JOIN);
        assertThat(joinStrategySelector.select(plan, 101))
                .isEqualTo(JoinStrategy.DUCKDB_HASH_JOIN);
        assertThat(joinStrategySelector.select(plan, 500))
                .isEqualTo(JoinStrategy.DUCKDB_HASH_JOIN);
    }
}
