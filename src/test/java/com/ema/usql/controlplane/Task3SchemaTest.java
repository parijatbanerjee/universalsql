package com.ema.usql.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 acceptance test: verifies Flyway applies all migrations and seed data is correct.
 * Uses Testcontainers so no external DB is required.
 */
@SpringBootTest
@Testcontainers
class Task3SchemaTest {

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

    @Autowired
    TenantConfigService tenantConfigService;

    @Autowired
    SourceCatalogRegistry sourceCatalogRegistry;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayMigrationsApplyCleanly() {
        // If Flyway failed, the context would not start — reaching here means migrations succeeded.
        // Verify schema tables exist by counting them via information_schema.
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name IN " +
                "('tenant','tenant_config','source_catalog','adapter_registry'," +
                "'principal_closure','resource_acl','oauth_connection','policy'," +
                "'audit_event','job_state','query_stats')",
                Integer.class
        );
        assertThat(tableCount).isEqualTo(11);
    }

    @Test
    void acmeTenantExistsWithCorrectKekId() {
        TenantConfig acme = tenantConfigService.findById("acme");

        assertThat(acme.tenantId()).isEqualTo("acme");
        assertThat(acme.name()).isEqualTo("Acme Corp");
        assertThat(acme.kekId()).isEqualTo("acme-kek-1");
        assertThat(acme.status()).isEqualTo("active");
        assertThat(acme.config()).containsKey("max_query_rows");
    }

    @Test
    void alicePrincipalClosureIncludesPlatAndCore() {
        List<String> alicePrincipals = jdbc.queryForList(
                "SELECT principal_id FROM principal_closure WHERE tenant_id = 'acme' AND user_id = 'alice'",
                String.class
        );

        assertThat(alicePrincipals).containsExactlyInAnyOrder("project:PLAT", "project:CORE");
    }

    @Test
    void bobPrincipalClosureIncludesOnlyCore() {
        List<String> bobPrincipals = jdbc.queryForList(
                "SELECT principal_id FROM principal_closure WHERE tenant_id = 'acme' AND user_id = 'bob'",
                String.class
        );

        assertThat(bobPrincipals).containsExactly("project:CORE");
    }

    @Test
    void sourceCatalogHasJiraAndGithub() {
        SourceCatalogEntry jira = sourceCatalogRegistry.findByConnector("jira");
        assertThat(jira.connectorId()).isEqualTo("jira");
        assertThat(jira.tableName()).isEqualTo("jira_issues");
        assertThat(jira.columns()).contains("project_key");
        assertThat(jira.capabilities()).contains("rls").contains("true");

        SourceCatalogEntry github = sourceCatalogRegistry.findByConnector("github");
        assertThat(github.connectorId()).isEqualTo("github");
        assertThat(github.tableName()).isEqualTo("github_prs");
    }
}
