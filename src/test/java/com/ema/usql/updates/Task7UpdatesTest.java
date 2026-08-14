package com.ema.usql.updates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ema.usql.knowledgecache.TenantDuckDbRegistry;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.knowledgecache.api.Watermark;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 7 acceptance tests for the Updates Manager.
 * Uses Testcontainers for Postgres and WireMock servers started programmatically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "usql.scheduling.enabled=false",
                "usql.connectors.jira.url=http://localhost:18083",
                "usql.connectors.github.url=http://localhost:18084"
        })
@AutoConfigureMockMvc
@Testcontainers
class Task7UpdatesTest {

    private static final int JIRA_PORT = 18083;
    private static final int GITHUB_PORT = 18084;

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
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration");
    }

    private static WireMockServer jiraServer;
    private static WireMockServer githubServer;

    @Autowired
    private PeriodicUpdater periodicUpdater;

    @Autowired
    private TenantDuckDbRegistry duckDbRegistry;

    @Autowired
    private WatermarkStore watermarkStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startWireMock() {
        jiraServer = new WireMockServer(WireMockConfiguration.options().port(JIRA_PORT));
        githubServer = new WireMockServer(WireMockConfiguration.options().port(GITHUB_PORT));
        jiraServer.start();
        githubServer.start();

        // Stub normal Jira response
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "total": 2,
                                  "issues": [
                                    {"key": "PLAT-1", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "High"}, "assignee": {"accountId": "alice"}, "summary": "Fix login bug", "created": "2024-01-01T10:00:00Z", "updated": "2024-01-15T10:00:00Z"}},
                                    {"key": "PLAT-2", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "Medium"}, "assignee": {"accountId": "bob"}, "summary": "Add OAuth support", "created": "2024-01-02T10:00:00Z", "updated": "2024-01-16T10:00:00Z"}}
                                  ]
                                }
                                """)));

        // Stub normal GitHub response
        githubServer.stubFor(get(urlPathMatching("/repos/acme/issues/pulls"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"number": 101, "title": "PLAT-1: fix login", "state": "open", "user": {"login": "alice"}, "created_at": "2024-01-10T10:00:00Z", "updated_at": "2024-01-20T10:00:00Z"}
                                ]
                                """)));
    }

    @AfterAll
    static void stopWireMock() {
        if (jiraServer != null) jiraServer.stop();
        if (githubServer != null) githubServer.stop();
    }

    // -----------------------------------------------------------------------
    // Test 1: PeriodicUpdater populates DuckDB and advances watermark
    // -----------------------------------------------------------------------

    @Test
    void periodicUpdaterPopulatesDuckDbAndAdvancesWatermark() throws Exception {
        // Trigger manually (scheduler is disabled)
        periodicUpdater.runUpdate();

        // Verify DuckDB has Jira records for "acme" tenant
        Connection conn = duckDbRegistry.getConnection("acme");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM jira_issues");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            int count = rs.getInt(1);
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        // Verify watermark was set
        Watermark watermark = watermarkStore.getWatermark("jira", "jira_issues", "acme");
        assertThat(watermark).isNotNull();
        assertThat(watermark.lastSyncedAt()).isNotNull();
        assertThat(watermark.lastSyncedAt().toEpochMilli())
                .isGreaterThan(Instant.now().minusSeconds(60).toEpochMilli());

        // Verify job state was updated in Postgres
        List<Map<String, Object>> jobRows = jdbc.queryForList(
                "SELECT * FROM job_state WHERE tenant_id = 'acme' AND connector_id = 'jira'");
        assertThat(jobRows).isNotEmpty();
        assertThat(jobRows.get(0).get("status")).isEqualTo("IDLE");
        assertThat(jobRows.get(0).get("watermark")).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Test 2: Valid webhook event updates a row in DuckDB
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    void validWebhookUpdatesRowInDuckDb() throws Exception {
        // Ensure DuckDB is initialized for acme by triggering a sync first
        periodicUpdater.runUpdate();

        // Record time before webhook
        Instant beforeWebhook = Instant.now();

        // Post a webhook event for an existing issue
        WebhookEvent event = new WebhookEvent(
                "acme",
                "PLAT-1",
                "issue_updated",
                Map.of(
                        "issue_key", "PLAT-WEBHOOK-1",
                        "project_key", "PLAT",
                        "status", "In Progress",
                        "summary", "Updated via webhook"
                )
        );

        mockMvc.perform(post("/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().is2xxSuccessful());

        // Verify DuckDB has the updated row
        Connection conn = duckDbRegistry.getConnection("acme");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sourced_at FROM jira_issues WHERE issue_key = 'PLAT-WEBHOOK-1'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            String sourcedAt = rs.getString("sourced_at");
            assertThat(sourcedAt).isNotNull();

            // sourced_at should be after the beforeWebhook timestamp
            Instant sourcedAtInstant = Instant.parse(sourcedAt);
            assertThat(sourcedAtInstant).isAfterOrEqualTo(beforeWebhook.minusSeconds(1));
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: Malformed/poisoned webhook event goes to DLQ, not blocking others
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    void poisonedWebhookGoesToDlqWithoutBlockingOthers() throws Exception {
        // Count DLQ entries before
        Integer dlqBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dlq_event", Integer.class);

        // Post a webhook with a missing tenantId (should fail validation)
        String malformedJson = """
                {"eventType": "issue_updated", "payload": {"issue_key": "BAD-1"}}
                """;

        mockMvc.perform(post("/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().is2xxSuccessful()); // Returns 202 Accepted (not 500)

        // Verify DLQ has one new entry
        Integer dlqAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dlq_event", Integer.class);
        assertThat(dlqAfter).isEqualTo(dlqBefore + 1);

        // Verify the DLQ entry has error info
        List<Map<String, Object>> dlqRows = jdbc.queryForList(
                "SELECT * FROM dlq_event ORDER BY received_at DESC LIMIT 1");
        assertThat(dlqRows).isNotEmpty();
        assertThat(dlqRows.get(0).get("error_message")).isNotNull();
        assertThat(dlqRows.get(0).get("connector_id")).isEqualTo("jira");

        // Verify other rows are untouched (Jira data still present after the poisoned event)
        periodicUpdater.runUpdate();
        Connection conn = duckDbRegistry.getConnection("acme");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM jira_issues");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
        }
    }
}
