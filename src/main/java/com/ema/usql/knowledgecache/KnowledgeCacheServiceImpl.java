package com.ema.usql.knowledgecache;

import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.crypto.api.EncryptionContext;
import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.crypto.api.WrappedDek;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.planner.MaskApplier;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.ResultColumn;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.telemetry.api.Telemetry;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowledge cache service backed by per-tenant DuckDB instances.
 * Handles encrypted storage (reporter_email_enc, author_email_enc) using envelope encryption.
 * Applies CLS masking and ACL principal filtering at query execution time.
 */
public class KnowledgeCacheServiceImpl implements KnowledgeCacheService {

    private static final String JIRA_TABLE = "jira_issues";
    private static final String GITHUB_TABLE = "github_prs";
    private static final String PURPOSE_REPORTER = "reporter_email";
    private static final String PURPOSE_AUTHOR = "author_email";

    private final TenantDuckDbRegistry registry;
    private final WatermarkStore watermarkStore;
    private final KmsModule kmsModule;
    private final MaskApplier maskApplier;
    private final Telemetry telemetry;

    public KnowledgeCacheServiceImpl(
            TenantDuckDbRegistry registry,
            WatermarkStore watermarkStore,
            KmsModule kmsModule,
            MaskApplier maskApplier,
            Telemetry telemetry) {
        this.registry = registry;
        this.watermarkStore = watermarkStore;
        this.kmsModule = kmsModule;
        this.maskApplier = maskApplier;
        this.telemetry = telemetry;
    }

    /**
     * Backwards-compatible constructor for tests that don't need CLS masking.
     */
    public KnowledgeCacheServiceImpl(
            TenantDuckDbRegistry registry,
            WatermarkStore watermarkStore,
            KmsModule kmsModule,
            Telemetry telemetry) {
        this(registry, watermarkStore, kmsModule, new MaskApplier(), telemetry);
    }

    @Override
    public QueryResult execute(Fragment fragment, TenantContext tenantContext,
                               ClsMaskSet clsMaskSet, Set<String> principalSet) {
        // Build effective SQL: inject ACL second-enforcement layer if principal set is non-empty
        String effectiveSql = buildAclFilteredSql(fragment.sql(), principalSet);

        Connection conn = registry.getConnection(tenantContext.tenantId());
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(effectiveSql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            List<ResultColumn> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(new ResultColumn(meta.getColumnName(i), meta.getColumnTypeName(i)));
            }

            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnName(i);
                    if ("reporter_email_enc".equals(colName)) {
                        // Decrypt the reporter email
                        byte[] encryptedEmail = readBlob(rs, i);
                        byte[] wrappedDekBytes = readBlob(rs, "wrapped_dek");
                        if (encryptedEmail != null && wrappedDekBytes != null) {
                            String decrypted = decryptField(encryptedEmail, wrappedDekBytes,
                                    tenantContext.tenantId(), PURPOSE_REPORTER);
                            // Apply CLS masking for reporter_email
                            String maskType = clsMaskSet != null
                                    ? clsMaskSet.maskedColumns().get("reporter_email")
                                    : null;
                            if (maskType != null) {
                                row.add(maskApplier.maskEmail(decrypted, maskType));
                            } else {
                                row.add(decrypted);
                            }
                        } else {
                            row.add(null);
                        }
                    } else if ("author_email_enc".equals(colName)) {
                        // Decrypt the author email
                        byte[] encryptedEmail = readBlob(rs, i);
                        byte[] wrappedDekBytes = readBlob(rs, "wrapped_dek");
                        if (encryptedEmail != null && wrappedDekBytes != null) {
                            String decrypted = decryptField(encryptedEmail, wrappedDekBytes,
                                    tenantContext.tenantId(), PURPOSE_AUTHOR);
                            // Apply CLS masking for author_email
                            String maskType = clsMaskSet != null
                                    ? clsMaskSet.maskedColumns().get("author_email")
                                    : null;
                            if (maskType != null) {
                                row.add(maskApplier.maskEmail(decrypted, maskType));
                            } else {
                                row.add(decrypted);
                            }
                        } else {
                            row.add(null);
                        }
                    } else if ("wrapped_dek".equals(colName)) {
                        // Skip wrapped_dek column - it's an internal field
                        row.add(null);
                    } else {
                        row.add(rs.getObject(i));
                    }
                }
                rows.add(row);
            }

            return new QueryResult(columns, rows, Map.of());
        } catch (SQLException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to execute cache query: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(List<ConnectorRecord> records, TenantContext tenantContext) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // Detect table type from the first record's fields
        ConnectorRecord first = records.get(0);
        if (first.fields().containsKey("issue_key")) {
            writeJiraIssues(records, tenantContext);
        } else if (first.fields().containsKey("pr_number")) {
            writeGithubPrs(records, tenantContext);
        }
    }

    @Override
    public Watermark getWatermark(String connector, String table, String tenantId) {
        return watermarkStore.getWatermark(connector, table, tenantId);
    }

    // -------------------------------------------------------------------------
    // ACL second-enforcement layer
    // -------------------------------------------------------------------------

    /**
     * Build a SQL string with an ACL filter injected into the WHERE clause.
     * Uses DuckDB's {@code list_intersect} to check that {@code acl_principals} overlaps
     * with the user's principal set.
     *
     * <p>This is defense-in-depth: even if the RLS predicate is broken, the ACL layer
     * still prevents cross-tenant/cross-project data leakage.
     *
     * @param originalSql  the original SQL (may already have WHERE clause from RLS injection)
     * @param principalSet the user's full principal set
     * @return modified SQL with ACL filter, or original SQL if principal set is empty
     */
    private String buildAclFilteredSql(String originalSql, Set<String> principalSet) {
        if (principalSet == null || principalSet.isEmpty()) {
            return originalSql;
        }

        // Check if this query references a table that has acl_principals
        String upperSql = originalSql.toUpperCase();
        boolean hasAclPrincipals = upperSql.contains("JIRA_ISSUES")
                || upperSql.contains("GITHUB_PRS");
        if (!hasAclPrincipals) {
            return originalSql;
        }

        // Build the list literal for DuckDB: ['project:PLAT', 'project:CORE']
        String principalList = principalSet.stream()
                .map(p -> "'" + p.replace("'", "''") + "'")
                .sorted()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));

        // Use array_length(list_intersect(acl_principals, [...])) > 0 for multi-value check.
        // DuckDB: cardinality() only works on MAPs; array_length() works on lists.
        String aclFilter = "(acl_principals IS NULL OR array_length(list_intersect(acl_principals, "
                + principalList + ")) > 0)";

        // Inject ACL filter into WHERE clause via string manipulation.
        // We check whether the SQL already has a WHERE clause (case-insensitive).
        // DuckDB's list syntax (e.g. ['val1', 'val2']) is not understood by JSqlParser,
        // so we use string manipulation instead of AST injection.
        String upperOriginal = originalSql.toUpperCase();

        // Find position to inject: before ORDER BY / LIMIT / GROUP BY / end
        // We look for keywords that come after WHERE
        String injected = injectAclFilterIntoSql(originalSql, upperOriginal, aclFilter);
        return injected;
    }

    /**
     * Inject the ACL filter into the SQL WHERE clause using string manipulation.
     * This avoids JSqlParser issues with DuckDB-specific array syntax.
     */
    private String injectAclFilterIntoSql(String sql, String upperSql, String aclFilter) {
        int whereIdx = upperSql.indexOf(" WHERE ");
        if (whereIdx >= 0) {
            // Find where the WHERE predicate ends (before ORDER BY / LIMIT / GROUP BY)
            int whereBodyStart = whereIdx + 7; // skip " WHERE "
            String afterWhereUpper = upperSql.substring(whereBodyStart);
            int whereBodyEnd = findClauseInsertPoint(afterWhereUpper);
            // whereBodyEnd is relative to afterWhereUpper — convert to absolute
            int absWhereEnd = whereBodyStart + whereBodyEnd;

            String beforeWhere = sql.substring(0, whereIdx);
            String whereBody = sql.substring(whereBodyStart, absWhereEnd);
            String afterClause = sql.substring(absWhereEnd);

            return beforeWhere + " WHERE " + aclFilter + " AND (" + whereBody + ")" + afterClause;
        } else {
            // No WHERE clause — find the position before ORDER BY / LIMIT / GROUP BY
            int insertAt = findClauseInsertPoint(upperSql);
            String beforeClause = sql.substring(0, insertAt);
            String afterClause = sql.substring(insertAt);
            return beforeClause + " WHERE " + aclFilter + afterClause;
        }
    }

    private int findClauseInsertPoint(String upperSql) {
        // Find the earliest of ORDER BY, LIMIT, GROUP BY, HAVING
        int[] positions = {
            upperSql.lastIndexOf(" ORDER BY "),
            upperSql.lastIndexOf(" LIMIT "),
            upperSql.lastIndexOf(" GROUP BY "),
            upperSql.lastIndexOf(" HAVING ")
        };
        int earliest = upperSql.length();
        for (int pos : positions) {
            if (pos >= 0 && pos < earliest) {
                earliest = pos;
            }
        }
        return earliest;
    }

    // -------------------------------------------------------------------------
    // Jira write
    // -------------------------------------------------------------------------

    private void writeJiraIssues(List<ConnectorRecord> records, TenantContext ctx) {
        Connection conn = registry.getConnection(ctx.tenantId());
        // Note: acl_principals (VARCHAR[]) is omitted from ON CONFLICT DO UPDATE SET
        // because DuckDB 1.1.x does not support list-type columns in conflict update clauses.
        String sql = """
                INSERT INTO jira_issues
                    (issue_key, project_key, status, priority, assignee_id,
                     reporter_email_enc, summary, created_at, updated_at,
                     acl_principals, sourced_at, wrapped_dek)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (issue_key) DO UPDATE SET
                    project_key = excluded.project_key,
                    status = excluded.status,
                    priority = excluded.priority,
                    assignee_id = excluded.assignee_id,
                    reporter_email_enc = excluded.reporter_email_enc,
                    summary = excluded.summary,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    sourced_at = excluded.sourced_at,
                    wrapped_dek = excluded.wrapped_dek
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ConnectorRecord record : records) {
                Map<String, Object> f = record.fields();

                // Encrypt reporter_email if present
                String reporterEmail = (String) f.get("reporter_email");
                byte[] encryptedEmail = null;
                byte[] wrappedDekBytes = null;

                if (reporterEmail != null) {
                    EncryptionContext encCtx = new EncryptionContext(ctx.tenantId(), PURPOSE_REPORTER);
                    WrappedDek wrappedDek = kmsModule.generateDek(ctx.tenantId(), encCtx);
                    SecretKey dek = kmsModule.unwrapDek(ctx.tenantId(), wrappedDek, encCtx);
                    encryptedEmail = encryptField(reporterEmail.getBytes(java.nio.charset.StandardCharsets.UTF_8), dek, encCtx);
                    wrappedDekBytes = wrappedDek.bytes();
                }

                String now = Instant.now().toString();

                // acl_principals from record fields (may be String[] or List<String>)
                String[] aclPrincipals = extractAclPrincipals(f.get("acl_principals"));

                ps.setString(1, (String) f.get("issue_key"));
                ps.setString(2, nvl(f.get("project_key")));
                ps.setString(3, nvl(f.get("status")));
                ps.setString(4, nvl(f.get("priority")));
                ps.setString(5, nvl(f.get("assignee_id")));
                ps.setBytes(6, encryptedEmail);
                ps.setString(7, nvl(f.get("summary")));
                ps.setString(8, nvl(f.get("created_at")));
                ps.setString(9, nvl(f.get("updated_at")));
                // Store acl_principals as DuckDB VARCHAR[] using setArray
                if (aclPrincipals != null) {
                    ps.setObject(10, conn.createArrayOf("VARCHAR", aclPrincipals));
                } else {
                    ps.setObject(10, null);
                }
                ps.setString(11, now);
                ps.setBytes(12, wrappedDekBytes);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to write Jira issues to cache: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // GitHub write
    // -------------------------------------------------------------------------

    private void writeGithubPrs(List<ConnectorRecord> records, TenantContext ctx) {
        Connection conn = registry.getConnection(ctx.tenantId());
        // Note: acl_principals (VARCHAR[]) is omitted from ON CONFLICT DO UPDATE SET
        // because DuckDB 1.1.x does not support list-type columns in conflict update clauses.
        String sql = """
                INSERT INTO github_prs
                    (pr_number, repo, title, state, author_id,
                     author_email_enc, linked_issue_key, created_at, updated_at,
                     acl_principals, sourced_at, wrapped_dek)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (pr_number) DO UPDATE SET
                    repo = excluded.repo,
                    title = excluded.title,
                    state = excluded.state,
                    author_id = excluded.author_id,
                    author_email_enc = excluded.author_email_enc,
                    linked_issue_key = excluded.linked_issue_key,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    sourced_at = excluded.sourced_at,
                    wrapped_dek = excluded.wrapped_dek
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ConnectorRecord record : records) {
                Map<String, Object> f = record.fields();

                String authorEmail = (String) f.get("author_email");
                byte[] encryptedEmail = null;
                byte[] wrappedDekBytes = null;

                if (authorEmail != null) {
                    EncryptionContext encCtx = new EncryptionContext(ctx.tenantId(), PURPOSE_AUTHOR);
                    WrappedDek wrappedDek = kmsModule.generateDek(ctx.tenantId(), encCtx);
                    SecretKey dek = kmsModule.unwrapDek(ctx.tenantId(), wrappedDek, encCtx);
                    encryptedEmail = encryptField(authorEmail.getBytes(java.nio.charset.StandardCharsets.UTF_8), dek, encCtx);
                    wrappedDekBytes = wrappedDek.bytes();
                }

                String now = Instant.now().toString();
                Object prNumber = f.get("pr_number");
                int prNum = prNumber instanceof Integer ? (Integer) prNumber
                        : (prNumber instanceof Number ? ((Number) prNumber).intValue() : 0);

                String[] aclPrincipals = extractAclPrincipals(f.get("acl_principals"));

                ps.setInt(1, prNum);
                ps.setString(2, nvl(f.get("repo")));
                ps.setString(3, nvl(f.get("title")));
                ps.setString(4, nvl(f.get("state")));
                ps.setString(5, nvl(f.get("author_id")));
                ps.setBytes(6, encryptedEmail);
                ps.setString(7, nvl(f.get("linked_issue_key")));
                ps.setString(8, nvl(f.get("created_at")));
                ps.setString(9, nvl(f.get("updated_at")));
                if (aclPrincipals != null) {
                    ps.setObject(10, conn.createArrayOf("VARCHAR", aclPrincipals));
                } else {
                    ps.setObject(10, null);
                }
                ps.setString(11, now);
                ps.setBytes(12, wrappedDekBytes);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to write GitHub PRs to cache: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Crypto helpers — inline AES/GCM (knowledgecache is an allowed SecretKey module)
    // Wire format: [12-byte IV][ciphertext+GCM tag]
    // EncryptionContext is bound as AAD: "tenantId:purpose" UTF-8 bytes
    // -------------------------------------------------------------------------

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom secureRandom = new SecureRandom();

    private byte[] encryptField(byte[] plaintext, SecretKey dek, EncryptionContext ctx) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aadBytes(ctx));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer buf = ByteBuffer.allocate(IV_BYTES + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();
        } catch (Exception e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to encrypt field", e);
        }
    }

    private String decryptField(byte[] ivAndCiphertext, byte[] wrappedDekBytes, String tenantId, String purpose) {
        try {
            EncryptionContext ctx = new EncryptionContext(tenantId, purpose);
            WrappedDek wrappedDek = new WrappedDek(wrappedDekBytes);
            SecretKey dek = kmsModule.unwrapDek(tenantId, wrappedDek, ctx);

            ByteBuffer buf = ByteBuffer.wrap(ivAndCiphertext);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aadBytes(ctx));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (UsqlException e) {
            throw e;
        } catch (Exception e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to decrypt field", e);
        }
    }

    private static byte[] aadBytes(EncryptionContext ctx) {
        return (ctx.tenantId() + ":" + ctx.purpose()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nvl(Object obj) {
        return obj == null ? null : obj.toString();
    }

    @SuppressWarnings("unchecked")
    private static String[] extractAclPrincipals(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String[] arr) {
            return arr;
        }
        if (value instanceof List<?> list) {
            return ((List<String>) list).toArray(new String[0]);
        }
        return null;
    }

    /**
     * Read BLOB bytes from a ResultSet column using getBlob() to work around
     * DuckDB JDBC's SQLFeatureNotSupportedException for getBytes().
     */
    private static byte[] readBlob(ResultSet rs, int columnIndex) throws SQLException {
        java.sql.Blob blob = rs.getBlob(columnIndex);
        if (blob == null) {
            return null;
        }
        long len = blob.length();
        if (len == 0) {
            return new byte[0];
        }
        return blob.getBytes(1, (int) len);
    }

    private static byte[] readBlob(ResultSet rs, String columnName) throws SQLException {
        java.sql.Blob blob = rs.getBlob(columnName);
        if (blob == null) {
            return null;
        }
        long len = blob.length();
        if (len == 0) {
            return new byte[0];
        }
        return blob.getBytes(1, (int) len);
    }
}
