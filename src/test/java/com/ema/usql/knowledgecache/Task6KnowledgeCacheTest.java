package com.ema.usql.knowledgecache;

import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.crypto.LocalKmsModule;
import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryPath;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.telemetry.api.Telemetry;
import com.ema.usql.telemetry.api.Span;
import com.ema.usql.telemetry.api.StructuredLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6 acceptance tests for the knowledge cache service.
 * Pure unit tests — no Spring context or containers needed.
 */
class Task6KnowledgeCacheTest {

    @TempDir
    Path tempDir;

    private TenantDuckDbRegistry registry;
    private KmsModule kmsModule;
    private WatermarkStore watermarkStore;
    private KnowledgeCacheServiceImpl cacheService;

    private static final String TENANT_ID = "acme";
    private static final TenantContext TENANT_CTX = new TenantContext(TENANT_ID, "alice", "acme-kek-1");

    @BeforeEach
    void setUp() {
        Path kmsDir = tempDir.resolve("kms");
        registry = new TenantDuckDbRegistry(tempDir);
        kmsModule = new LocalKmsModule(kmsDir);
        watermarkStore = new WatermarkStore(registry);
        cacheService = new KnowledgeCacheServiceImpl(registry, watermarkStore, kmsModule, noOpTelemetry());
    }

    // -----------------------------------------------------------------------
    // Test 1: Write 100 Jira issues with encrypted reporter_email, read back decrypted
    // -----------------------------------------------------------------------

    @Test
    void writeAndReadJiraIssuesWithEncryptedEmail() {
        // Arrange: build 100 Jira issue records with reporter_email
        List<ConnectorRecord> records = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("issue_key", "PLAT-" + i);
            fields.put("project_key", "PLAT");
            fields.put("status", "Open");
            fields.put("priority", "High");
            fields.put("assignee_id", "alice");
            fields.put("reporter_email", "reporter" + i + "@acme.com");
            fields.put("summary", "Issue " + i);
            fields.put("created_at", "2024-01-01T10:00:00Z");
            fields.put("updated_at", "2024-01-15T10:00:00Z");
            records.add(new ConnectorRecord(fields));
        }

        // Act: write
        cacheService.write(records, TENANT_CTX);

        // Act: read back via execute (SELECT that includes reporter_email_enc and wrapped_dek)
        Fragment fragment = new Fragment(
                "frag-1", "jira",
                "SELECT issue_key, project_key, status, reporter_email_enc, wrapped_dek FROM jira_issues ORDER BY issue_key",
                List.of(), "conn-ref", 100, QueryPath.CACHE);
        QueryResult result = cacheService.execute(fragment, TENANT_CTX);

        // Assert: 100 rows returned
        assertThat(result.rows()).hasSize(100);

        // Assert: decrypted emails are correct
        // Column order: issue_key=0, project_key=1, status=2, reporter_email_enc=3, wrapped_dek=4
        int emailColIdx = -1;
        for (int i = 0; i < result.columns().size(); i++) {
            if ("reporter_email_enc".equals(result.columns().get(i).name())) {
                emailColIdx = i;
                break;
            }
        }
        assertThat(emailColIdx).isGreaterThanOrEqualTo(0);

        // Verify the first and last rows have decrypted emails
        // Rows are ordered by issue_key alphabetically: PLAT-1, PLAT-10, PLAT-100, ...
        boolean foundEmailDecrypted = false;
        for (List<Object> row : result.rows()) {
            Object decryptedEmail = row.get(emailColIdx);
            assertThat(decryptedEmail).isInstanceOf(String.class);
            assertThat((String) decryptedEmail).endsWith("@acme.com");
            foundEmailDecrypted = true;
        }
        assertThat(foundEmailDecrypted).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 2: Raw DuckDB query shows reporter_email_enc as bytes (unreadable)
    // -----------------------------------------------------------------------

    @Test
    void rawQueryShowsEncryptedBlobNotReadableEmail() throws Exception {
        // Arrange: write 1 Jira issue with a known email
        String expectedEmail = "secret@acme.com";
        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", "PLAT-RAW");
        fields.put("project_key", "PLAT");
        fields.put("status", "Open");
        fields.put("priority", "High");
        fields.put("assignee_id", "alice");
        fields.put("reporter_email", expectedEmail);
        fields.put("summary", "Raw test issue");
        fields.put("created_at", "2024-01-01T10:00:00Z");
        fields.put("updated_at", "2024-01-15T10:00:00Z");
        cacheService.write(List.of(new ConnectorRecord(fields)), TENANT_CTX);

        // Act: open a raw JDBC connection to the same DuckDB file
        Path dbFile = tempDir.resolve("tenants").resolve(TENANT_ID).resolve("knowledge.duckdb");
        String jdbcUrl = "jdbc:duckdb:" + dbFile.toAbsolutePath();

        // DuckDB doesn't allow two connections to the same file by default in read-write mode
        // Use the existing connection from the registry
        Connection conn = registry.getConnection(TENANT_ID);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT reporter_email_enc FROM jira_issues WHERE issue_key = 'PLAT-RAW'");
             ResultSet rs = ps.executeQuery()) {

            assertThat(rs.next()).isTrue();

            // The raw column should be bytes, not a readable email string
            // DuckDB JDBC requires getBlob() for BLOB columns (getBytes() throws SQLFeatureNotSupportedException)
            java.sql.Blob blob = rs.getBlob("reporter_email_enc");
            assertThat(blob).isNotNull();
            byte[] rawBytes = blob.getBytes(1, (int) blob.length());
            assertThat(rawBytes).isNotNull();
            assertThat(rawBytes.length).isGreaterThan(0);

            // The bytes must NOT equal the email's UTF-8 bytes
            byte[] emailBytes = expectedEmail.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(rawBytes).isNotEqualTo(emailBytes);

            // The raw bytes as a string should NOT contain the original email
            String rawAsString = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(rawAsString).doesNotContain(expectedEmail);
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: Watermark operations
    // -----------------------------------------------------------------------

    @Test
    void watermarkLifecycle() {
        // Trigger DuckDB initialization for tenant
        registry.getConnection(TENANT_ID);

        // Initially no watermark
        Watermark initial = cacheService.getWatermark("jira", "jira_issues", TENANT_ID);
        assertThat(initial).isNull();

        // Update watermark
        Instant syncedAt = Instant.parse("2024-01-15T12:00:00Z");
        watermarkStore.updateWatermark("jira", "jira_issues", TENANT_ID, syncedAt, "cursor-100");

        // Read back
        Watermark updated = cacheService.getWatermark("jira", "jira_issues", TENANT_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.source()).isEqualTo("jira");
        assertThat(updated.tableName()).isEqualTo("jira_issues");
        assertThat(updated.lastSyncedAt()).isEqualTo(syncedAt);
        assertThat(updated.lastCursor()).isEqualTo("cursor-100");

        // Update again — should overwrite
        Instant syncedAt2 = Instant.parse("2024-01-16T12:00:00Z");
        watermarkStore.updateWatermark("jira", "jira_issues", TENANT_ID, syncedAt2, "cursor-200");

        Watermark updated2 = cacheService.getWatermark("jira", "jira_issues", TENANT_ID);
        assertThat(updated2.lastSyncedAt()).isEqualTo(syncedAt2);
        assertThat(updated2.lastCursor()).isEqualTo("cursor-200");
    }

    // -----------------------------------------------------------------------
    // No-op Telemetry for tests
    // -----------------------------------------------------------------------

    private static Telemetry noOpTelemetry() {
        return new Telemetry() {
            @Override
            public Span span(String name, Map<String, String> attrs) {
                return new Span() {
                    @Override public void recordException(Throwable t) {}
                    @Override public void setAttribute(String key, String value) {}
                    @Override public void close() {}
                };
            }
            @Override public void counter(String name, Map<String, String> tags) {}
            @Override public void timer(String name, Duration d, Map<String, String> tags) {}
            @Override public void gauge(String name, Supplier<Number> v, Map<String, String> tags) {}
            @Override
            public StructuredLogger logger(Class<?> clazz) {
                return new StructuredLogger() {
                    @Override public void info(String msg, Map<String, Object> fields) {}
                    @Override public void warn(String msg, Map<String, Object> fields) {}
                    @Override public void error(String msg, Throwable t, Map<String, Object> fields) {}
                };
            }
        };
    }
}
