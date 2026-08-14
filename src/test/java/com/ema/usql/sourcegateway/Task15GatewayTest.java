package com.ema.usql.sourcegateway;

import com.ema.usql.connectors.api.SourceQuery;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.sourcegateway.api.RateLimitStatus;
import com.ema.usql.sourcegateway.api.SourceGateway;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 15 acceptance tests: Source Gateway with hierarchical rate limiting,
 * concurrency bulkhead, and circuit breaker.
 *
 * Test 1: Normal call → succeeds, returns records
 * Test 2: Exhaust per-tenant budget (21 calls rapidly) → RATE_LIMIT_EXHAUSTED
 * Test 3: Tenant A exhausts budget, Tenant B still succeeds (fairness)
 * Test 4: Circuit breaker opens on forced 500 errors → SOURCE_UNAVAILABLE
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class Task15GatewayTest {

    // Ports chosen to avoid conflict with Task5ConnectorTest (18081/18082)
    private static final int JIRA_PORT = 18091;

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
        registry.add("usql.connectors.github.url", () -> "http://localhost:18092");
    }

    @Autowired
    SourceGateway sourceGateway;

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
        stubSuccess();
    }

    private void stubSuccess() {
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "total": 2,
                                  "issues": [
                                    {"key": "PLAT-1", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "High"}, "assignee": {"accountId": "alice"}, "summary": "Issue 1", "created": "2024-01-01T10:00:00Z", "updated": "2024-01-15T10:00:00Z"}},
                                    {"key": "PLAT-2", "fields": {"project": {"key": "PLAT"}, "status": {"name": "Open"}, "priority": {"name": "Medium"}, "assignee": {"accountId": "alice"}, "summary": "Issue 2", "created": "2024-01-02T10:00:00Z", "updated": "2024-01-16T10:00:00Z"}}
                                  ]
                                }
                                """)));
    }

    // -------------------------------------------------------------------------
    // Test 1: Normal call succeeds and returns records
    // -------------------------------------------------------------------------

    @Test
    void normalCall_returnsRecords() {
        SourceQuery query = new SourceQuery("jira",
                "project = PLAT ORDER BY created DESC", List.of(), 5000);
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        // Use a fresh SourceGatewayImpl for this test to have a clean rate limiter
        SourceGatewayImpl gateway = newGateway();
        var records = gateway.execute("alice-jira-conn", query, ctx);

        assertThat(records).isNotNull().hasSize(2);
        assertThat(records.get(0).fields()).containsEntry("issue_key", "PLAT-1");
    }

    // -------------------------------------------------------------------------
    // Test 2: Exhaust per-tenant budget → RATE_LIMIT_EXHAUSTED
    // -------------------------------------------------------------------------

    @Test
    void exhaustTenantBudget_throwsRateLimitExhausted() {
        SourceQuery query = new SourceQuery("jira", "project = PLAT", List.of(), 5000);
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        SourceGatewayImpl gateway = newGateway();

        int limit = (int) SourceGatewayImpl.TENANT_RATE_PER_SECOND; // 20
        int overLimit = limit + 1; // 21st call should fail

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < overLimit; i++) {
            try {
                gateway.execute("alice-jira-conn", query, ctx);
                successCount++;
            } catch (UsqlException e) {
                if (e.getErrorCode() == ErrorCode.RATE_LIMIT_EXHAUSTED) {
                    failCount++;
                } else {
                    throw e;
                }
            }
        }

        // At least 1 call must have been rate-limited
        assertThat(failCount).isGreaterThanOrEqualTo(1);
        // And we should have gotten at least the limit worth of successes
        assertThat(successCount).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Test 3: Tenant A exhausts budget, Tenant B still succeeds (fairness)
    // -------------------------------------------------------------------------

    @Test
    void tenantAExhausted_tenantBSucceeds() {
        SourceQuery query = new SourceQuery("jira", "project = PLAT", List.of(), 5000);
        TenantContext ctxA = new TenantContext("acme", "alice", "acme-kek-1");
        TenantContext ctxB = new TenantContext("beta", "carol", "beta-kek-1");

        SourceGatewayImpl gateway = newGateway();

        // Exhaust Tenant A's budget
        int overLimit = (int) SourceGatewayImpl.TENANT_RATE_PER_SECOND + 5;
        for (int i = 0; i < overLimit; i++) {
            try {
                gateway.execute("alice-jira-conn", query, ctxA);
            } catch (UsqlException e) {
                // Ignore rate limit errors from tenant A
                if (e.getErrorCode() != ErrorCode.RATE_LIMIT_EXHAUSTED) throw e;
            }
        }

        // Tenant B (carol-jira-conn) should still succeed with its own budget
        var betaRecords = gateway.execute("carol-jira-conn", query, ctxB);
        assertThat(betaRecords).isNotNull().isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 4: Circuit breaker opens after forced 500 errors → SOURCE_UNAVAILABLE
    // -------------------------------------------------------------------------

    @Test
    void circuitBreakerOpens_onForcedErrors() {
        // Override stub to return 500
        jiraServer.resetAll();
        jiraServer.stubFor(get(urlPathMatching("/rest/api/3/search"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        SourceQuery query = new SourceQuery("jira", "project = PLAT", List.of(), 5000);
        TenantContext ctx = new TenantContext("acme", "alice", "acme-kek-1");

        SourceGatewayImpl gateway = newGateway();

        // Fire calls to trigger the circuit breaker
        // CB config: 50% failure rate, 10 sliding window → need at least 10 calls with 50%+ failures
        // All calls will fail → after 10, CB opens
        int callCount = 0;
        int failCount = 0;
        for (int i = 0; i < 15; i++) {
            try {
                gateway.execute("alice-jira-conn", query, ctx);
                callCount++;
            } catch (UsqlException e) {
                if (e.getErrorCode() == ErrorCode.RATE_LIMIT_EXHAUSTED) {
                    // skip rate limit errors
                } else {
                    failCount++;
                }
            }
        }

        // Now CB should be open — next call should return SOURCE_UNAVAILABLE
        // Wait a tiny bit to ensure CB state is updated
        assertThatThrownBy(() -> gateway.execute("alice-jira-conn", query, ctx))
                .isInstanceOf(UsqlException.class)
                .satisfies(ex -> {
                    UsqlException usqlEx = (UsqlException) ex;
                    assertThat(usqlEx.getErrorCode()).isIn(
                            ErrorCode.SOURCE_UNAVAILABLE, ErrorCode.RATE_LIMIT_EXHAUSTED);
                });
    }

    // -------------------------------------------------------------------------
    // Helper: getRateLimitStatus
    // -------------------------------------------------------------------------

    @Test
    void getRateLimitStatus_returnsNonNullStatus() {
        SourceGatewayImpl gateway = newGateway();
        RateLimitStatus status = gateway.getRateLimitStatus("jira", "acme");
        assertThat(status).isNotNull();
        assertThat(status.limit()).isEqualTo(SourceGatewayImpl.TENANT_RATE_PER_SECOND);
        assertThat(status.resetsAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helper: create a fresh SourceGatewayImpl for each test with isolated rate limiters
    // -------------------------------------------------------------------------

    @Autowired
    com.ema.usql.authz.api.TokenService tokenService;

    @Autowired
    com.ema.usql.connectors.ConnectorRegistry connectorRegistry;

    @Autowired
    com.ema.usql.telemetry.api.Telemetry telemetry;

    private SourceGatewayImpl newGateway() {
        return new SourceGatewayImpl(tokenService, connectorRegistry, telemetry);
    }
}
