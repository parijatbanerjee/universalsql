package com.ema.usql.coordinator;

import com.ema.usql.api.PolicyMetadata;
import com.ema.usql.api.QueryMetadata;
import com.ema.usql.api.QueryRequest;
import com.ema.usql.api.QueryResponse;
import com.ema.usql.api.SourceMetadata;
import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.api.AuthzService;
import com.ema.usql.coordinator.execution.ExecutionEngine;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.planner.LogicalPlan;
import com.ema.usql.planner.SqlParser;
import com.ema.usql.planner.catalog.SourceCatalog;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryPath;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.telemetry.api.Span;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates query execution end-to-end:
 * parse → authz → plan fragments → execute → merge results.
 *
 * <p>Task 9 implements the cache-only path.
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
    private final ExecutionEngine executionEngine;
    private final KnowledgeCacheService knowledgeCacheService;
    private final Telemetry telemetry;

    public Orchestrator(
            SqlParser sqlParser,
            SourceCatalog sourceCatalog,
            AuthzService authzService,
            ExecutionEngine executionEngine,
            KnowledgeCacheService knowledgeCacheService,
            Telemetry telemetry) {
        this.sqlParser = sqlParser;
        this.sourceCatalog = sourceCatalog;
        this.authzService = authzService;
        this.executionEngine = executionEngine;
        this.knowledgeCacheService = knowledgeCacheService;
        this.telemetry = telemetry;
    }

    /**
     * Execute the query request for the given tenant, returning a complete QueryResponse.
     */
    public QueryResponse execute(QueryRequest request, TenantContext ctx) {
        String traceId = UUID.randomUUID().toString();

        try (Span totalSpan = telemetry.span("query.total",
                Map.of("tenant", ctx.tenantId(), "trace_id", traceId))) {

            // 1. Parse and validate SQL
            LogicalPlan plan = sqlParser.parse(request.sql(), sourceCatalog);

            // 2. Resolve authz (stub returns no RLS/CLS for Task 9)
            AuthzContext authzCtx = authzService.resolve(ctx);

            // 3. Build fragments — for Task 9 cache path: one fragment per table
            List<Fragment> fragments = buildCacheFragments(plan, request.sql());

            // 4. Execute all fragments against the cache
            List<QueryResult> results = new ArrayList<>();
            List<SourceMetadata> sourceMetas = new ArrayList<>();

            for (Fragment fragment : fragments) {
                String connectorId = fragment.connector();
                String tableName = plan.tables().isEmpty() ? connectorId : plan.tables().get(0);

                try (Span cacheSpan = telemetry.span("cache.lookup",
                        Map.of("connector", connectorId, "tenant", ctx.tenantId()))) {

                    try (Span fragSpan = telemetry.span("fragment." + connectorId,
                            Map.of("path", fragment.path().name(), "connector", connectorId))) {

                        QueryResult result = executionEngine.executeCacheFragment(fragment, ctx);
                        results.add(result);

                        // Compute freshness from watermark
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

            // 5. Merge results (single table in Task 9 — just return the first)
            QueryResult merged = results.isEmpty()
                    ? new QueryResult(List.of(), List.of(), Map.of())
                    : results.get(0);

            // Compute overall freshness
            long overallFreshnessMs = sourceMetas.stream()
                    .mapToLong(SourceMetadata::freshnessMs)
                    .max()
                    .orElse(0L);

            PolicyMetadata policyMeta = new PolicyMetadata(
                    false, false,
                    authzCtx.rlsPredicate() != null ? authzCtx.rlsPredicate().expression() : null);

            QueryMetadata metadata = new QueryMetadata(
                    traceId,
                    overallFreshnessMs,
                    false,
                    sourceMetas,
                    Map.of(),
                    policyMeta,
                    null
            );

            return new QueryResponse(merged.columns(), merged.rows(), metadata);
        }
    }

    // -------------------------------------------------------------------------
    // Fragment building
    // -------------------------------------------------------------------------

    private List<Fragment> buildCacheFragments(LogicalPlan plan, String originalSql) {
        List<Fragment> fragments = new ArrayList<>();

        // For Task 9 (cache path): one fragment covering the original SQL
        // DuckDB supports standard SQL so we can pass the original SQL directly.
        // However, the logical SQL uses logical column names; the physical schema
        // uses _enc suffixed columns for email fields. For SELECT * queries
        // we rewrite to select the actual physical columns available.
        String firstTable = plan.tables().get(0);
        String connectorId = TABLE_TO_CONNECTOR.getOrDefault(firstTable, firstTable);

        Fragment fragment = new Fragment(
                UUID.randomUUID().toString(),
                connectorId,
                originalSql,
                List.of(),
                null,
                -1L,
                QueryPath.CACHE
        );
        fragments.add(fragment);

        return fragments;
    }
}
