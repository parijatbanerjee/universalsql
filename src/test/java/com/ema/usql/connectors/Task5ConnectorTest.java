package com.ema.usql.connectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ema.usql.connectors.api.CapabilityDescriptor;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.connectors.api.Credential;
import com.ema.usql.connectors.api.SourceQuery;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.telemetry.api.Telemetry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 5 acceptance tests for the Connector SDK.
 * WireMock servers are started programmatically (no Docker or Spring context needed).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Task5ConnectorTest {

    private static final int JIRA_PORT = 18081;
    private static final int GITHUB_PORT = 18082;

    private WireMockServer jiraServer;
    private WireMockServer githubServer;

    private JiraConnector jiraConnector;
    private GithubConnector githubConnector;

    // Minimal no-op Telemetry for tests
    private final Telemetry telemetry = new NoOpTelemetry();

    @BeforeAll
    void startServers() {
        jiraServer = new WireMockServer(WireMockConfiguration.options().port(JIRA_PORT));
        githubServer = new WireMockServer(WireMockConfiguration.options().port(GITHUB_PORT));
        jiraServer.start();
        githubServer.start();
    }

    @AfterAll
    void stopServers() {
        jiraServer.stop();
        githubServer.stop();
    }

    @BeforeEach
    void setUpConnectors() {
        // Build connectors pointing to local WireMock servers
        // Use a 3000ms read timeout so the slow (2500ms) test succeeds
        RestClient.Builder builder = RestClient.builder();

        jiraConnector = new JiraConnector(
                "http://localhost:" + JIRA_PORT,
                builder,
                telemetry);

        githubConnector = new GithubConnector(
                "http://localhost:" + GITHUB_PORT,
                RestClient.builder(),
                telemetry);
    }

    // -----------------------------------------------------------------------
    // Test 1: normal Jira fetch returns 3 issues
    // -----------------------------------------------------------------------

    @Test
    void normalJiraFetch() {
        // Arrange
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-RateLimit-Remaining", "94")
                        .withBody("""
                                {
                                  "total": 3,
                                  "issues": [
                                    {"key": "PLAT-1", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "High"}, "assignee": {"accountId": "alice"}, "summary": "Fix login bug", "created": "2024-01-01T10:00:00Z", "updated": "2024-01-15T10:00:00Z"}},
                                    {"key": "PLAT-2", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "Medium"}, "assignee": {"accountId": "bob"}, "summary": "Add OAuth support", "created": "2024-01-02T10:00:00Z", "updated": "2024-01-16T10:00:00Z"}},
                                    {"key": "CORE-1", "fields": {"project": {"key": "CORE"}, "status": {"name": "In Progress"}, "priority": {"name": "Low"}, "assignee": null, "summary": "Refactor DB layer", "created": "2024-01-03T10:00:00Z", "updated": "2024-01-17T10:00:00Z"}}
                                  ]
                                }
                                """)));

        SourceQuery query = new SourceQuery("jira", "project = PLAT", List.of(), 5000);
        Credential cred = new Credential("test-connection-ref");

        // Act
        List<ConnectorRecord> records = jiraConnector.fetch(query, cred);

        // Assert
        assertThat(records).hasSize(3);
        assertThat(records.get(0).fields()).containsEntry("issue_key", "PLAT-1");
        assertThat(records.get(0).fields()).containsEntry("project_key", "PLAT");
        assertThat(records.get(0).fields()).containsEntry("status", "Open");
        assertThat(records.get(0).fields()).containsEntry("priority", "High");
        assertThat(records.get(0).fields()).containsEntry("assignee_id", "alice");
        assertThat(records.get(0).fields()).containsEntry("summary", "Fix login bug");

        assertThat(records.get(2).fields()).containsEntry("issue_key", "CORE-1");
        assertThat(records.get(2).fields()).containsEntry("assignee_id", null);
    }

    // -----------------------------------------------------------------------
    // Test 2: normal GitHub fetch returns 2 PRs
    // -----------------------------------------------------------------------

    @Test
    void normalGithubFetch() {
        // Arrange
        githubServer.stubFor(get(urlPathMatching("/repos/acme/issues/pulls"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"number": 101, "title": "PLAT-1: fix login", "state": "open", "user": {"login": "alice"}, "created_at": "2024-01-10T10:00:00Z", "updated_at": "2024-01-20T10:00:00Z"},
                                  {"number": 102, "title": "PLAT-2: add oauth", "state": "open", "user": {"login": "bob"}, "created_at": "2024-01-11T10:00:00Z", "updated_at": "2024-01-21T10:00:00Z"}
                                ]
                                """)));

        SourceQuery query = new SourceQuery("github", "state = open", List.of(), 5000);
        Credential cred = new Credential("test-connection-ref");

        // Act
        List<ConnectorRecord> records = githubConnector.fetch(query, cred);

        // Assert
        assertThat(records).hasSize(2);

        ConnectorRecord first = records.get(0);
        assertThat(first.fields()).containsEntry("pr_number", 101);
        assertThat(first.fields()).containsEntry("title", "PLAT-1: fix login");
        assertThat(first.fields()).containsEntry("state", "open");
        assertThat(first.fields()).containsEntry("author_id", "alice");
        assertThat(first.fields()).containsEntry("linked_issue_key", "PLAT-1");

        ConnectorRecord second = records.get(1);
        assertThat(second.fields()).containsEntry("pr_number", 102);
        assertThat(second.fields()).containsEntry("linked_issue_key", "PLAT-2");
    }

    // -----------------------------------------------------------------------
    // Test 3: Jira slow response (2500ms delay) - connector returns data after delay
    // -----------------------------------------------------------------------

    @Test
    void jiraSlowResponse() {
        // Arrange: stub with 2500ms delay
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .inScenario("slow-jira")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(2500)
                        .withBody("{\"total\": 0, \"issues\": []}")));

        SourceQuery query = new SourceQuery("jira", "project = SLOW", List.of(), 3000);
        Credential cred = new Credential("test-slow");

        long start = System.currentTimeMillis();

        // Act: should succeed after 2500ms (no timeout configured in this connector)
        List<ConnectorRecord> records = jiraConnector.fetch(query, cred);
        long elapsed = System.currentTimeMillis() - start;

        // Assert: got empty response and it took at least 2500ms
        assertThat(records).isEmpty();
        assertThat(elapsed).isGreaterThanOrEqualTo(2500L);
    }

    // -----------------------------------------------------------------------
    // Test 4: Jira 429 rate limit → UsqlException(RATE_LIMIT_EXHAUSTED)
    // -----------------------------------------------------------------------

    @Test
    void jira429RateLimit() {
        // Arrange: stub with 429
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .inScenario("rate-limited-jira")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "5")
                        .withBody("{\"errorMessages\": [\"Rate limit exceeded\"]}")));

        SourceQuery query = new SourceQuery("jira", "project = PLAT", List.of(), 5000);
        Credential cred = new Credential("test-rate-limited");

        // Act + Assert
        assertThatThrownBy(() -> jiraConnector.fetch(query, cred))
                .isInstanceOf(UsqlException.class)
                .satisfies(ex -> {
                    UsqlException usqlEx = (UsqlException) ex;
                    assertThat(usqlEx.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXHAUSTED);
                    assertThat(usqlEx.getMessage()).contains("Retry-After");
                });
    }

    // -----------------------------------------------------------------------
    // Test 5: Capabilities
    // -----------------------------------------------------------------------

    @Test
    void jiraCapabilities() {
        CapabilityDescriptor caps = jiraConnector.getCapabilities();
        assertThat(caps.connector()).isEqualTo("jira");
        assertThat(caps.supportedFilters()).containsExactly("=", "IN", ">", "<");
        assertThat(caps.maxPageSize()).isEqualTo(50);
    }

    @Test
    void githubCapabilities() {
        CapabilityDescriptor caps = githubConnector.getCapabilities();
        assertThat(caps.connector()).isEqualTo("github");
        assertThat(caps.supportedFilters()).containsExactly("=", "IN");
        assertThat(caps.maxPageSize()).isEqualTo(100);
    }

    // -----------------------------------------------------------------------
    // Minimal no-op Telemetry for tests
    // -----------------------------------------------------------------------

    private static class NoOpTelemetry implements Telemetry {
        @Override
        public com.ema.usql.telemetry.api.Span span(String name, java.util.Map<String, String> attrs) {
            return new com.ema.usql.telemetry.api.Span() {
                @Override public void recordException(Throwable t) {}
                @Override public void setAttribute(String key, String value) {}
                @Override public void close() {}
            };
        }

        @Override
        public void counter(String name, java.util.Map<String, String> tags) {}

        @Override
        public void timer(String name, Duration d, java.util.Map<String, String> tags) {}

        @Override
        public void gauge(String name, java.util.function.Supplier<Number> v, java.util.Map<String, String> tags) {}

        @Override
        public com.ema.usql.telemetry.api.StructuredLogger logger(Class<?> clazz) {
            return new com.ema.usql.telemetry.api.StructuredLogger() {
                @Override public void info(String msg, java.util.Map<String, Object> fields) {}
                @Override public void warn(String msg, java.util.Map<String, Object> fields) {}
                @Override public void error(String msg, Throwable t, java.util.Map<String, Object> fields) {}
            };
        }
    }
}
