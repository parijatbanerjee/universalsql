package com.ema.usql.coordinator;

import com.ema.usql.api.PolicyMetadata;
import com.ema.usql.api.QueryMetadata;
import com.ema.usql.api.QueryRequest;
import com.ema.usql.api.QueryResponse;
import com.ema.usql.api.SourceMetadata;
import com.ema.usql.audit.api.AuditEvent;
import com.ema.usql.audit.api.AuditService;
import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.api.AuthzService;
import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.authz.api.RlsPredicate;
import com.ema.usql.coordinator.execution.ExecutionEngine;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.planner.LogicalPlan;
import com.ema.usql.planner.PolicyCompiler;
import com.ema.usql.planner.SqlParser;
import com.ema.usql.planner.catalog.SourceCatalog;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryPath;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.telemetry.api.Span;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates query execution end-to-end:
 * parse → authz → RLS inject → CLS validate → execute → CLS mask → audit → return.
 */
@Service
public class Orchestrator {

    // Maps logical table names to connector identifiers
    private static final Map<String, String> TABLE_TO_CONNECTOR = Map.of(
            "jira_issues", "jira",
            "github_prs", "github"
    );

    private final SqlParser sqlParser;
    private final SourceCatalog sourceCatalog;
    private final AuthzService authzService;
    private final PolicyCompiler policyCompiler;
    private final ExecutionEngine executionEngine;
    private final KnowledgeCacheService knowledgeCacheService;
    private final AuditService auditService;
    private final Telemetry telemetry;

    public Orchestrator(
            SqlParser sqlParser,
            SourceCatalog sourceCatalog,
            AuthzService authzService,
            PolicyCompiler policyCompiler,
            ExecutionEngine executionEngine,
            KnowledgeCacheService knowledgeCacheService,
            AuditService auditService,
            Telemetry telemetry) {
        this.sqlParser = sqlParser;
        this.sourceCatalog = sourceCatalog;
        this.authzService = authzService;
        this.policyCompiler = policyCompiler;
        this.executionEngine = executionEngine;
        this.knowledgeCacheService = knowledgeCacheService;
        this.auditService = auditService;
        this.telemetry = telemetry;
    }

    /**
     * Execute the query request for the given tenant, returning a complete QueryResponse.
     *
     * <p>Flow:
     * <ol>
     *   <li>Parse and validate SQL</li>
     *   <li>Resolve authz context (principals, RLS template, CLS masks)</li>
     *   <li>Compile RLS predicate from template + principals</li>
     *   <li>Validate masked columns not in predicates (CLS check)</li>
     *   <li>Inject RLS into SQL</li>
     *   <li>Execute fragments against cache (CLS masking + ACL enforcement in cache layer)</li>
     *   <li>Record audit event</li>
     *   <li>Return response</li>
     * </ol>
     */
    public QueryResponse execute(QueryRequest request, TenantContext ctx) {
        String traceId = UUID.randomUUID().toString();

        try (Span totalSpan = telemetry.span("query.total",
                Map.of("tenant", ctx.tenantId(), "trace_id", traceId))) {

            // 1. Parse and validate SQL
            LogicalPlan plan = sqlParser.parse(request.sql(), sourceCatalog);

            // 2. Resolve authz (principals, RLS template, CLS masks)
            AuthzContext authzCtx = authzService.resolve(ctx);

            // 3. Compile RLS predicate from template + principals
            RlsPredicate rlsPredicate = authzCtx.rlsPredicate() != null && authzCtx.rlsPredicate().expression() != null
                    ? policyCompiler.compile(authzCtx.rlsPredicate().expression(), authzCtx)
                    : new RlsPredicate(null);

            // 4. Validate masked columns are not in predicates (ENTITLEMENT_DENIED if violated)
            ClsMaskSet clsMaskSet = authzCtx.clsMaskSet();
            try {
                sqlParser.validateMaskedColumnsNotInPredicates(request.sql(), clsMaskSet);
            } catch (UsqlException e) {
                // Record denial audit event
                recordAuditEvent(traceId, ctx, plan, request.sql(), "DENY", e.getMessage());
                throw e;
            }

            // 5. Inject RLS into SQL
            String effectiveSql = policyCompiler.injectIntoSql(request.sql(), rlsPredicate);

            // 6. Build fragments
            List<Fragment> fragments = buildCacheFragments(plan, effectiveSql);

            // 7. Execute all fragments against the cache (with CLS + ACL enforcement)
            List<QueryResult> results = new ArrayList<>();
            List<SourceMetadata> sourceMetas = new ArrayList<>();

            for (Fragment fragment : fragments) {
                String connectorId = fragment.connector();
                String tableName = plan.tables().isEmpty() ? connectorId : plan.tables().get(0);

                try (Span cacheSpan = telemetry.span("cache.lookup",
                        Map.of("connector", connectorId, "tenant", ctx.tenantId()))) {

                    try (Span fragSpan = telemetry.span("fragment." + connectorId,
                            Map.of("path", fragment.path().name(), "connector", connectorId))) {

                        QueryResult result = executionEngine.executeCacheFragment(
                                fragment, ctx, clsMaskSet, authzCtx.principalSet());
                        results.add(result);

                        Watermark watermark = knowledgeCacheService.getWatermark(
                                connectorId, tableName, ctx.tenantId());
                        long freshnessMs = watermark != null ? watermark.ageMs() : 0L;

                        sourceMetas.add(new SourceMetadata(
                                connectorId,
                                fragment.path().name(),
                                freshnessMs
                        ));
                    }
                }
            }

            // 8. Merge results
            QueryResult merged = results.isEmpty()
                    ? new QueryResult(List.of(), List.of(), Map.of())
                    : results.get(0);

            long overallFreshnessMs = sourceMetas.stream()
                    .mapToLong(SourceMetadata::freshnessMs)
                    .max()
                    .orElse(0L);

            boolean rlsApplied = rlsPredicate.expression() != null;
            boolean clsApplied = clsMaskSet != null && !clsMaskSet.maskedColumns().isEmpty();

            PolicyMetadata policyMeta = new PolicyMetadata(
                    rlsApplied,
                    clsApplied,
                    rlsPredicate.expression());

            QueryMetadata metadata = new QueryMetadata(
                    traceId,
                    overallFreshnessMs,
                    false,
                    sourceMetas,
                    Map.of(),
                    policyMeta,
                    null
            );

            // 9. Record audit event (ALLOW)
            recordAuditEvent(traceId, ctx, plan, request.sql(), "ALLOW", null);

            return new QueryResponse(merged.columns(), merged.rows(), metadata);
        }
    }

    // -------------------------------------------------------------------------
    // Fragment building
    // -------------------------------------------------------------------------

    private List<Fragment> buildCacheFragments(LogicalPlan plan, String effectiveSql) {
        List<Fragment> fragments = new ArrayList<>();

        String firstTable = plan.tables().get(0);
        String connectorId = TABLE_TO_CONNECTOR.getOrDefault(firstTable, firstTable);

        Fragment fragment = new Fragment(
                UUID.randomUUID().toString(),
                connectorId,
                effectiveSql,
                List.of(),
                null,
                -1L,
                QueryPath.CACHE
        );
        fragments.add(fragment);

        return fragments;
    }

    // -------------------------------------------------------------------------
    // Audit helper
    // -------------------------------------------------------------------------

    private void recordAuditEvent(String traceId, TenantContext ctx,
                                  LogicalPlan plan, String sql,
                                  String decision, String reason) {
        try {
            String connectorId = plan != null && !plan.tables().isEmpty()
                    ? TABLE_TO_CONNECTOR.getOrDefault(plan.tables().get(0), plan.tables().get(0))
                    : "unknown";

            AuditEvent event = new AuditEvent(
                    traceId,
                    ctx.tenantId(),
                    ctx.userId(),
                    connectorId,
                    "QUERY",
                    plan != null ? plan.tables() : List.of(),
                    decision,
                    reason,
                    sqlHash(ctx.tenantId(), sql)
            );
            auditService.record(event);
        } catch (Exception e) {
            // Audit failures must not break the query path — log but swallow
            telemetry.span("audit.failure", Map.of("error", e.getMessage())).close();
        }
    }

    private String sqlHash(String tenantId, String sql) {
        try {
            String salted = tenantId + ":" + sql;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(salted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
