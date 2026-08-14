package com.ema.usql;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.knowledgecache.KnowledgeCacheServiceImpl;
import com.ema.usql.knowledgecache.TenantDuckDbRegistry;
import com.ema.usql.knowledgecache.WatermarkStore;
import com.ema.usql.shared.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 20 acceptance test: Secret-leak scan.
 *
 * Verifies that sensitive data (reporter email, OAuth tokens) never appears
 * in log output during a query execution. Uses a Logback {@link ListAppender}
 * to capture all log events during the test and scans their formatted messages.
 *
 * Two assertions:
 * 1. No log event contains the reporter email seeded into DuckDB
 *    ("secret-leak-test@example.com").
 * 2. No log event contains the fake OAuth token value ("alice-jira-conn-token").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
class SecretLeakScanTest {

    private static final String SECRET_EMAIL = "secret-leak-test@example.com";
    private static final String SECRET_TOKEN = "alice-jira-conn-token";
    private static final String TENANT_ID = "acme";
    private static final TenantContext TENANT_CTX = new TenantContext(TENANT_ID, "alice", "acme-kek-1");

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
    private MockMvc mockMvc;

    @Autowired
    private TenantDuckDbRegistry duckDbRegistry;

    @Autowired
    private WatermarkStore watermarkStore;

    @Autowired
    private KnowledgeCacheServiceImpl cacheService;

    @BeforeEach
    void seedSecretData() throws Exception {
        // Clean the DuckDB table
        java.sql.Connection conn = duckDbRegistry.getConnection(TENANT_ID);
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM jira_issues");
        }

        // Insert a row with the secret email — this should never appear in logs
        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", "SECRET-1");
        fields.put("project_key", "PLAT");
        fields.put("status", "Open");
        fields.put("priority", "High");
        fields.put("assignee_id", "alice");
        fields.put("reporter_email", SECRET_EMAIL);
        fields.put("summary", "Secret issue");
        fields.put("created_at", "2024-01-01T10:00:00Z");
        fields.put("updated_at", "2024-01-15T10:00:00Z");
        fields.put("acl_principals", List.of("project:PLAT", "project:CORE"));

        List<ConnectorRecord> records = new ArrayList<>();
        records.add(new ConnectorRecord(fields));
        cacheService.write(records, TENANT_CTX);

        watermarkStore.updateWatermark("jira", "jira_issues", TENANT_ID,
                Instant.now().minusSeconds(30), "cursor-secret");
    }

    /**
     * Canonical test 1 (Task 20): Verifies that no sensitive data leaks into log output.
     *
     * <p>Pre-populates DuckDB with a row whose reporter_email is a known secret string,
     * executes a query via MockMvc, then scans all captured log events to ensure
     * the secret string and a fake OAuth token never appear in any log message.
     */
    @Test
    void noEmailLeaksInLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            // Execute a cache query — reporter_email is encrypted in DuckDB but
            // the decrypted value must NOT appear in logs
            mockMvc.perform(post("/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "sql": "SELECT issue_key, project_key, status FROM jira_issues LIMIT 25",
                                        "include_latest_data": false,
                                        "max_staleness_ms": 60000,
                                        "timeout_ms": 5000
                                    }
                                    """))
                    .andExpect(status().isOk());

            // Take a snapshot to avoid ConcurrentModificationException
            // (Spring's async log flushing may append during iteration)
            List<ILoggingEvent> events = new ArrayList<>(appender.list);
            for (ILoggingEvent event : events) {
                String message = event.getFormattedMessage();
                assertThat(message)
                        .as("Log message must not contain secret email — found in: %s", message)
                        .doesNotContain(SECRET_EMAIL);

                assertThat(message)
                        .as("Log message must not contain fake OAuth token — found in: %s", message)
                        .doesNotContain(SECRET_TOKEN);

                // Also check MDC properties
                if (event.getMDCPropertyMap() != null) {
                    event.getMDCPropertyMap().values().forEach(v -> {
                        assertThat(v)
                                .as("MDC value must not contain secret email")
                                .doesNotContain(SECRET_EMAIL);
                        assertThat(v)
                                .as("MDC value must not contain fake OAuth token")
                                .doesNotContain(SECRET_TOKEN);
                    });
                }
            }
        } finally {
            root.detachAppender(appender);
        }
    }
}
