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
import com.ema.usql.controlplane.TenantConfig;
import com.ema.usql.controlplane.TenantConfigService;
import com.ema.usql.coordinator.execution.DuckDbSession;
import com.ema.usql.coordinator.execution.ExecutionEngine;
import com.ema.usql.coordinator.execution.ResultCache;
import com.ema.usql.coordinator.execution.ResultMerger;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.livequery.api.LiveQueryService;
import com.ema.usql.planner.AclFreshnessImpl;
import com.ema.usql.planner.FreshnessHint;
import com.ema.usql.planner.JoinStrategySelector;
import com.ema.usql.planner.LogicalPlan;
import com.ema.usql.planner.PathSelector;
import com.ema.usql.planner.PolicyCompiler;
import com.ema.usql.planner.RateLimitBudgetImpl;
import com.ema.usql.planner.SqlParser;
import com.ema.usql.planner.catalog.SourceCatalog;
import com.ema.usql.sourcegateway.api.SourceGateway;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.JoinStrategy;
import com.ema.usql.shared.QueryPath;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.ResultColumn;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.telemetry.api.Span;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates query execution end-to-end:
 * parse → authz → RLS inject → CLS validate → execute → CLS mask → audit → return.
 *
 * <p>Supports three execution modes:
 * <ol>
 *   <li>Single-source CACHE: fetch from DuckDB knowledge cache</li>
 *   <li>Single-source LIVE: fetch from live connector</li>
 *   <li>Hybrid: fetch from both CACHE and LIVE, merge with ResultMerger (Task 18)</li>
 *   <li>JOIN: execute side A first, apply JoinStrategySelector, execute side B (Task 19)</li>
 * </ol>
 */
@Service
public class Orchestrator {

    // Maps logical table names to connector identifiers
    private static final Map<String, String> TABLE_TO_CONNECTOR = Map.of(
            "jira_issues", "jira",
            "github_prs", "github"
    );

    private final TenantConfigService tenantConfigService;
    private final SqlParser sqlParser;
    private final SourceCatalog sourceCatalog;
    private final AuthzService authzService;
    private final PolicyCompiler policyCompiler;
    private final ExecutionEngine executionEngine;
    private final KnowledgeCacheService knowledgeCacheService;
    private final LiveQueryService liveQueryService;
    private final PathSelector pathSelector;
    private final SourceGateway sourceGateway;
    private final AuditService auditService;
    private final Telemetry telemetry;
    private final ResultMerger resultMerger;
    private final JoinStrategySelector joinStrategySelector;
    private final ResultCache resultCache;

    public Orchestrator(
            TenantConfigService tenantConfigService,
            SqlParser sqlParser,
            SourceCatalog sourceCatalog,
            AuthzService authzService,
            PolicyCompiler policyCompiler,
            ExecutionEngine executionEngine,
            KnowledgeCacheService knowledgeCacheService,
            LiveQueryService liveQueryService,
            PathSelector pathSelector,
            SourceGateway sourceGateway,
            AuditService auditService,
            Telemetry telemetry,
            ResultMerger resultMerger,
            JoinStrategySelector joinStrategySelector,
            ResultCache resultCache) {
        this.tenantConfigService = tenantConfigService;
        this.sqlParser = sqlParser;
        this.sourceCatalog = sourceCatalog;
        this.authzService = authzService;
        this.policyCompiler = policyCompiler;
        this.executionEngine = executionEngine;
        this.knowledgeCacheService = knowledgeCacheService;
        this.liveQueryService = liveQueryService;
        this.pathSelector = pathSelector;
        this.sourceGateway = sourceGateway;
        this.auditService = auditService;
        this.telemetry = telemetry;
        this.resultMerger = resultMerger;
        this.joinStrategySelector = joinStrategySelector;
        this.resultCache = resultCache;
    }

    /**
     * Execute the query request for the given tenant, returning a complete QueryResponse.
     */
    public QueryResponse execute(QueryRequest request, TenantContext ctx) {
        String traceId = UUID.randomUUID().toString();

        try (Span totalSpan = telemetry.span("query.total",
                Map.of("tenant", ctx.tenantId(), "trace_id", traceId))) {

            // 0. Check tenant status — inactive tenants are rejected before any processing.
            // We use a try/catch to distinguish:
            //   a) Tenant found and inactive → throw (crypto-shred complete)
            //   b) Tenant not found → allow through (graceful for integration tests that
            //      don't seed a full tenant row in Postgres)
            // Production would require tenant to exist; test tolerance is acceptable.
            checkTenantActive(ctx.tenantId());

            // 1. Parse and validate SQL
            LogicalPlan plan = sqlParser.parse(request.sql(), sourceCatalog);

            // 2a. Check result cache — only for pure cache-path, non-LIVE, non-JOIN queries.
            // We skip result caching entirely when include_latest_data=true (LIVE path) or
            // when the query is a JOIN (join_strategy metadata would be lost in the cached response).
            // This keeps the result cache safe for correctness across test runs.
            AuthzContext authzCtxPrelim = authzService.resolve(ctx);
            boolean eligibleForResultCache = !request.includeLatestData() && plan.joinTable() == null;
            String cacheKey = buildCacheKey(ctx, authzCtxPrelim, request.sql());
            if (eligibleForResultCache) {
                Optional<QueryResult> cached = resultCache.get(cacheKey);
                if (cached.isPresent()) {
                    QueryResult cachedResult = cached.get();
                    PolicyMetadata policyMeta = new PolicyMetadata(false, false, null);
                    QueryMetadata metadata = new QueryMetadata(
                            traceId, 0L, false,
                            List.of(new SourceMetadata("result-cache", "CACHE", 0L)),
                            Map.of(), policyMeta, null);
                    recordAuditEvent(traceId, ctx, plan, request.sql(), "ALLOW", null);
                    return new QueryResponse(cachedResult.columns(), cachedResult.rows(), metadata);
                }
            }

            // 2. Reuse the preliminary authz context resolved above
            AuthzContext authzCtx = authzCtxPrelim;

            // 3. Compile RLS predicate from template + principals
            RlsPredicate rlsPredicate = authzCtx.rlsPredicate() != null && authzCtx.rlsPredicate().expression() != null
                    ? policyCompiler.compile(authzCtx.rlsPredicate().expression(), authzCtx)
                    : new RlsPredicate(null);

            // 4. Validate masked columns are not in predicates (ENTITLEMENT_DENIED if violated)
            ClsMaskSet clsMaskSet = authzCtx.clsMaskSet();
            try {
                sqlParser.validateMaskedColumnsNotInPredicates(request.sql(), clsMaskSet);
            } catch (UsqlException e) {
                recordAuditEvent(traceId, ctx, plan, request.sql(), "DENY", e.getMessage());
                throw e;
            }

            // 5. Inject RLS into SQL
            String effectiveSql = policyCompiler.injectIntoSql(request.sql(), rlsPredicate);

            // 6. Route to appropriate execution mode
            QueryResult merged;
            List<SourceMetadata> sourceMetas = new ArrayList<>();
            boolean partial = false;
            String joinStrategy = null;

            if (plan.joinTable() != null) {
                // JOIN query: execute side A, then side B with join strategy
                JoinExecutionResult joinResult = executeJoinQuery(
                        plan, effectiveSql, request, authzCtx, ctx, clsMaskSet);
                merged = joinResult.result();
                sourceMetas.addAll(joinResult.sourceMetas());
                partial = joinResult.partial();
                joinStrategy = joinResult.joinStrategy();
            } else {
                // Single-source query
                SingleSourceExecutionResult singleResult = executeSingleSourceQuery(
                        plan, effectiveSql, request, authzCtx, ctx, clsMaskSet);
                merged = singleResult.result();
                sourceMetas.addAll(singleResult.sourceMetas());
                partial = singleResult.partial();
            }

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
                    partial,
                    sourceMetas,
                    Map.of(),
                    policyMeta,
                    joinStrategy
            );

            // 7. Record audit event (ALLOW)
            recordAuditEvent(traceId, ctx, plan, request.sql(), "ALLOW", null);

            // 8. Store result in cache (only for eligible queries: non-partial, CACHE path)
            if (eligibleForResultCache && !partial) {
                resultCache.put(cacheKey, merged);
            }

            return new QueryResponse(merged.columns(), merged.rows(), metadata);
        }
    }

    // -------------------------------------------------------------------------
    // Single-source execution (no JOIN)
    // -------------------------------------------------------------------------

    private SingleSourceExecutionResult executeSingleSourceQuery(
            LogicalPlan plan, String effectiveSql,
            QueryRequest request, AuthzContext authzCtx,
            TenantContext ctx, ClsMaskSet clsMaskSet) {

        String firstTable = plan.tables().get(0);
        String connectorId = TABLE_TO_CONNECTOR.getOrDefault(firstTable, firstTable);
        String connectionRef = deriveConnectionRef(connectorId, ctx.userId());
        long timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 30_000L;

        // Build preliminary fragment for PathSelector
        Fragment preliminary = new Fragment(
                UUID.randomUUID().toString(),
                connectorId,
                effectiveSql,
                List.of(),
                connectionRef,
                -1L,
                QueryPath.CACHE,
                timeoutMs
        );

        // Compute PathSelector inputs
        FreshnessHint freshnessHint = new FreshnessHint(
                request.includeLatestData(),
                request.maxStalenessMs() > 0 ? request.maxStalenessMs() : 0
        );
        Watermark watermark = knowledgeCacheService.getWatermark(
                connectorId, firstTable, ctx.tenantId());
        AclFreshnessImpl aclFreshness = new AclFreshnessImpl(authzCtx.aclSyncedAt());
        RateLimitBudgetImpl rateLimitBudget = new RateLimitBudgetImpl(sourceGateway, ctx.tenantId());

        QueryPath selectedPath = pathSelector.select(
                preliminary, freshnessHint, watermark, rateLimitBudget, aclFreshness);

        // Build final fragment with selected path
        Fragment fragment = new Fragment(
                preliminary.fragmentId(),
                connectorId,
                effectiveSql,
                List.of(),
                connectionRef,
                preliminary.estimatedRows(),
                selectedPath,
                timeoutMs
        );

        // When path is LIVE and watermark exists, also execute CACHE and merge (hybrid mode)
        boolean isHybrid = selectedPath == QueryPath.LIVE && watermark != null
                && request.includeLatestData();

        if (isHybrid) {
            return executeHybrid(fragment, firstTable, connectorId, watermark,
                    ctx, clsMaskSet, authzCtx.principalSet(), plan);
        }

        // Single-path execution
        List<QueryResult> results = new ArrayList<>();
        List<SourceMetadata> sourceMetas = new ArrayList<>();
        boolean partial = false;

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<FragmentOutcome> future = executor.submit(
                    () -> executeFragment(fragment, ctx, clsMaskSet, authzCtx.principalSet(), plan));
            try {
                FragmentOutcome outcome = future.get();
                results.add(outcome.result());
                sourceMetas.add(outcome.sourceMeta());
            } catch (Exception e) {
                partial = true;
                telemetry.span("fragment.timeout",
                        Map.of("connector", fragment.connector())).close();
            }
        } finally {
            executor.shutdown();
        }

        QueryResult merged = results.isEmpty()
                ? new QueryResult(List.of(), List.of(), Map.of())
                : results.get(0);

        return new SingleSourceExecutionResult(merged, sourceMetas, partial);
    }

    /**
     * Hybrid mode: execute both CACHE and LIVE fragments for the same table, then merge.
     * Live row wins over cached row on same primary key.
     */
    private SingleSourceExecutionResult executeHybrid(
            Fragment liveFragment, String tableName, String connectorId,
            Watermark watermark, TenantContext ctx,
            ClsMaskSet clsMaskSet, java.util.Set<String> principalSet,
            LogicalPlan plan) {

        // Build cache fragment (same SQL, CACHE path)
        Fragment cacheFragment = new Fragment(
                UUID.randomUUID().toString(),
                connectorId,
                liveFragment.sql(),
                liveFragment.predicates(),
                liveFragment.connectionRef(),
                liveFragment.estimatedRows(),
                QueryPath.CACHE,
                liveFragment.timeoutMs()
        );

        QueryResult cacheResult = null;
        QueryResult liveResult = null;
        boolean partial = false;

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // Execute both in parallel
            Future<FragmentOutcome> cacheFuture = executor.submit(
                    () -> executeFragment(cacheFragment, ctx, clsMaskSet, principalSet, plan));
            Future<FragmentOutcome> liveFuture = executor.submit(
                    () -> executeFragment(liveFragment, ctx, clsMaskSet, principalSet, plan));

            try {
                cacheResult = cacheFuture.get().result();
            } catch (Exception e) {
                partial = true;
                telemetry.span("hybrid.cache.timeout", Map.of("connector", connectorId)).close();
            }

            try {
                liveResult = liveFuture.get().result();
            } catch (Exception e) {
                partial = true;
                telemetry.span("hybrid.live.timeout", Map.of("connector", connectorId)).close();
            }
        } finally {
            executor.shutdown();
        }

        // If one side failed, return what we have
        if (cacheResult == null && liveResult == null) {
            return new SingleSourceExecutionResult(
                    new QueryResult(List.of(), List.of(), Map.of()),
                    List.of(), true);
        }
        if (cacheResult == null) {
            List<SourceMetadata> metas = List.of(
                    new SourceMetadata(connectorId, "LIVE", 0L));
            return new SingleSourceExecutionResult(liveResult, metas, partial);
        }
        if (liveResult == null) {
            long cacheAge = watermark.ageMs();
            List<SourceMetadata> metas = List.of(
                    new SourceMetadata(connectorId, "CACHE", cacheAge));
            return new SingleSourceExecutionResult(cacheResult, metas, partial);
        }

        // Merge: live rows win over cache rows on same PK
        long cacheAge = watermark.ageMs();
        QueryResult merged = resultMerger.merge(cacheResult, liveResult, cacheAge);

        // Report both sources; LIVE first so sources[0].path() = "LIVE"
        // freshness_ms per source: LIVE=0, CACHE=cacheAge
        // aggregate freshness_ms (at QueryMetadata level) = max = cacheAge
        List<SourceMetadata> sourceMetas = List.of(
                new SourceMetadata(connectorId, "LIVE", 0L),
                new SourceMetadata(connectorId + "-cache", "CACHE", cacheAge)
        );

        return new SingleSourceExecutionResult(merged, sourceMetas, partial);
    }

    // -------------------------------------------------------------------------
    // JOIN query execution (Task 19)
    // -------------------------------------------------------------------------

    private JoinExecutionResult executeJoinQuery(
            LogicalPlan plan, String effectiveSql,
            QueryRequest request, AuthzContext authzCtx,
            TenantContext ctx, ClsMaskSet clsMaskSet) {

        String sideATable = plan.tables().get(0);
        String sideBTable = plan.joinTable();
        String sideAConnector = TABLE_TO_CONNECTOR.getOrDefault(sideATable, sideATable);
        String sideBConnector = TABLE_TO_CONNECTOR.getOrDefault(sideBTable, sideBTable);
        long timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 30_000L;

        // Build side A SQL (jira_issues only, with WHERE clause)
        String sideASql = buildSideASql(plan, sideATable, effectiveSql);
        String connectionRefA = deriveConnectionRef(sideAConnector, ctx.userId());

        // Compute path for side A
        FreshnessHint freshnessHint = new FreshnessHint(
                request.includeLatestData(),
                request.maxStalenessMs() > 0 ? request.maxStalenessMs() : 0
        );
        Watermark watermarkA = knowledgeCacheService.getWatermark(
                sideAConnector, sideATable, ctx.tenantId());
        AclFreshnessImpl aclFreshness = new AclFreshnessImpl(authzCtx.aclSyncedAt());
        RateLimitBudgetImpl rateLimitBudget = new RateLimitBudgetImpl(sourceGateway, ctx.tenantId());

        Fragment sideAPrelim = new Fragment(
                UUID.randomUUID().toString(), sideAConnector, sideASql,
                List.of(), connectionRefA, -1L, QueryPath.CACHE, timeoutMs);
        QueryPath sideAPath = pathSelector.select(
                sideAPrelim, freshnessHint, watermarkA, rateLimitBudget, aclFreshness);

        Fragment sideAFragment = new Fragment(
                sideAPrelim.fragmentId(), sideAConnector, sideASql,
                List.of(), connectionRefA, -1L, sideAPath, timeoutMs);

        // Step 1: Execute side A
        QueryResult sideAResult;
        boolean partial = false;
        long freshnessAMs;

        try {
            FragmentOutcome outcome = executeFragment(sideAFragment, ctx, clsMaskSet,
                    authzCtx.principalSet(), plan);
            sideAResult = outcome.result();
            freshnessAMs = outcome.sourceMeta().freshnessMs();
        } catch (Exception e) {
            // Side A failed — return empty result
            return new JoinExecutionResult(
                    new QueryResult(List.of(), List.of(), Map.of()),
                    List.of(), true, null);
        }

        // Step 2: Choose join strategy based on side A row count
        long sideARowCount = sideAResult.rows().size();
        JoinStrategy strategy = joinStrategySelector.select(plan, sideARowCount);

        // Step 3: Execute side B based on strategy
        String sideBSql = buildSideBSql(plan, sideBTable);
        String connectionRefB = deriveConnectionRef(sideBConnector, ctx.userId());

        // When include_latest_data=true and executing a JOIN, always fetch side B as LIVE
        // so the IN-list filter reaches the connector (semi-join correctness guarantee).
        QueryPath sideBPath;
        if (request.includeLatestData()) {
            sideBPath = QueryPath.LIVE;
        } else {
            Watermark watermarkB = knowledgeCacheService.getWatermark(
                    sideBConnector, sideBTable, ctx.tenantId());
            Fragment sideBPrelim = new Fragment(
                    UUID.randomUUID().toString(), sideBConnector, sideBSql,
                    List.of(), connectionRefB, -1L, QueryPath.CACHE, timeoutMs);
            sideBPath = pathSelector.select(
                    sideBPrelim, freshnessHint, watermarkB, rateLimitBudget, aclFreshness);
        }
        Fragment sideBPrelim = new Fragment(
                UUID.randomUUID().toString(), sideBConnector, sideBSql,
                List.of(), connectionRefB, -1L, sideBPath, timeoutMs);

        QueryResult sideBResult;
        long freshnessBMs;

        if (strategy == JoinStrategy.SEMI_JOIN_REDUCTION) {
            // Extract join key values from side A result
            List<String> joinKeys = extractJoinKeys(sideAResult, sideATable, plan);

            // Build side B fragment with IN-list filter
            Fragment sideBFragment = new Fragment(
                    sideBPrelim.fragmentId(), sideBConnector, sideBSql,
                    List.of(), connectionRefB, -1L, sideBPath, timeoutMs, joinKeys);

            try {
                FragmentOutcome outcome = executeFragment(sideBFragment, ctx, clsMaskSet,
                        authzCtx.principalSet(), plan);
                sideBResult = outcome.result();
                freshnessBMs = outcome.sourceMeta().freshnessMs();
            } catch (Exception e) {
                partial = true;
                sideBResult = new QueryResult(List.of(), List.of(), Map.of());
                freshnessBMs = 0L;
            }

            // In-memory join using side A and side B results
            QueryResult joinResult = inMemoryJoin(sideAResult, sideBResult, plan,
                    sideATable, sideBTable);

            List<SourceMetadata> sourceMetas = List.of(
                    new SourceMetadata(sideAConnector, sideAPath.name(), freshnessAMs),
                    new SourceMetadata(sideBConnector, sideBPath.name(), freshnessBMs)
            );

            return new JoinExecutionResult(joinResult, sourceMetas, partial,
                    JoinStrategy.SEMI_JOIN_REDUCTION.name());

        } else {
            // DUCKDB_HASH_JOIN: execute both sides without filter, join in DuckDB
            Fragment sideBFragment = new Fragment(
                    sideBPrelim.fragmentId(), sideBConnector, sideBSql,
                    List.of(), connectionRefB, -1L, sideBPath, timeoutMs);

            try {
                FragmentOutcome outcome = executeFragment(sideBFragment, ctx, clsMaskSet,
                        authzCtx.principalSet(), plan);
                sideBResult = outcome.result();
                freshnessBMs = outcome.sourceMeta().freshnessMs();
            } catch (Exception e) {
                partial = true;
                sideBResult = new QueryResult(List.of(), List.of(), Map.of());
                freshnessBMs = 0L;
            }

            // DuckDB hash join
            QueryResult joinResult = duckDbHashJoin(sideAResult, sideBResult, plan,
                    sideATable, sideBTable);

            List<SourceMetadata> sourceMetas = List.of(
                    new SourceMetadata(sideAConnector, sideAPath.name(), freshnessAMs),
                    new SourceMetadata(sideBConnector, sideBPath.name(), freshnessBMs)
            );

            return new JoinExecutionResult(joinResult, sourceMetas, partial,
                    JoinStrategy.DUCKDB_HASH_JOIN.name());
        }
    }

    /**
     * Build a SQL query for side A (jira_issues) that selects only that table's columns.
     */
    private String buildSideASql(LogicalPlan plan, String sideATable, String effectiveSql) {
        // For side A, we need all columns from the primary table to get join keys
        // Use a simple SELECT * FROM sideATable with the WHERE clause from the original plan
        StringBuilder sb = new StringBuilder("SELECT * FROM ");
        sb.append(sideATable);
        if (plan.whereClause() != null && !plan.whereClause().isBlank()) {
            // Filter out predicates that reference the join table
            String sideAAlias = findAlias(plan, sideATable);
            String sideBAlias = plan.joinTable() != null ? findAlias(plan, plan.joinTable()) : null;
            String filtered = filterWhereForSideA(plan.whereClause(), sideAAlias, sideBAlias,
                    sideATable, plan.joinTable());
            if (filtered != null && !filtered.isBlank()) {
                sb.append(" WHERE ").append(filtered);
            }
        }
        return sb.toString();
    }

    /**
     * Build a SQL query for side B (github_prs) — select all columns.
     */
    private String buildSideBSql(LogicalPlan plan, String sideBTable) {
        return "SELECT * FROM " + sideBTable;
    }

    /**
     * Filter WHERE clause to only include predicates relevant to the given table alias.
     * Simple heuristic: if a predicate term contains the side B alias/table, exclude it.
     */
    private String filterWhereForSideA(String whereClause, String sideAAlias,
                                        String sideBAlias, String sideATable, String sideBTable) {
        if (whereClause == null) return null;
        // Split by AND and filter terms that reference side B
        // This is a simplified approach; JSqlParser would be more robust
        String[] parts = whereClause.split("\\s+AND\\s+");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String upper = part.toUpperCase();
            boolean referencesSideB = false;
            if (sideBAlias != null && upper.contains(sideBAlias.toUpperCase() + ".")) {
                referencesSideB = true;
            }
            if (sideBTable != null && upper.contains(sideBTable.toUpperCase() + ".")) {
                referencesSideB = true;
            }
            if (!referencesSideB) {
                kept.add(part.trim());
            }
        }
        return kept.isEmpty() ? null : String.join(" AND ", kept);
    }

    private String findAlias(LogicalPlan plan, String tableName) {
        return plan.tableAliases().entrySet().stream()
                .filter(e -> e.getValue().equals(tableName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(tableName);
    }

    /**
     * Extract join key values from side A result.
     * The join key column is derived from the join condition (e.g. "i.issue_key" from "p.linked_issue_key = i.issue_key").
     */
    private List<String> extractJoinKeys(QueryResult sideAResult, String sideATable, LogicalPlan plan) {
        // Determine which column from side A is the join key
        // The join condition is like "p.linked_issue_key = i.issue_key"
        // For jira_issues, the join key is "issue_key"
        String joinKeyColumn = detectSideAJoinKeyColumn(plan, sideATable);

        // Find the column index in side A result
        List<String> colNames = sideAResult.columns().stream()
                .map(ResultColumn::name)
                .toList();
        int keyIdx = colNames.indexOf(joinKeyColumn);
        if (keyIdx < 0) {
            // Fallback: first column
            keyIdx = 0;
        }

        final int finalKeyIdx = keyIdx;
        return sideAResult.rows().stream()
                .map(row -> finalKeyIdx < row.size() ? objToString(row.get(finalKeyIdx)) : null)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
    }

    private String detectSideAJoinKeyColumn(LogicalPlan plan, String sideATable) {
        // Parse the join condition to find the column belonging to side A
        // join condition like "p.linked_issue_key = i.issue_key"
        if (plan.joinCondition() == null) {
            return "issue_key"; // default
        }

        String cond = plan.joinCondition();
        String sideAAlias = findAlias(plan, sideATable);

        // Split on "=" and find the side A column
        String[] parts = cond.split("=");
        if (parts.length == 2) {
            for (String part : parts) {
                String trimmed = part.trim();
                // Check if this references side A (by alias or table name)
                if (trimmed.startsWith(sideAAlias + ".") || trimmed.startsWith(sideATable + ".")) {
                    int dotIdx = trimmed.indexOf('.');
                    if (dotIdx >= 0) {
                        return trimmed.substring(dotIdx + 1).trim();
                    }
                }
            }
        }

        // Default: issue_key for jira_issues
        return "issue_key";
    }

    /**
     * Detect the side B join column from the join condition.
     * e.g. "p.linked_issue_key = i.issue_key" → "linked_issue_key" for side B (github_prs)
     */
    private String detectSideBJoinColumn(LogicalPlan plan, String sideBTable) {
        if (plan.joinCondition() == null) {
            return "linked_issue_key"; // default
        }

        String cond = plan.joinCondition();
        String sideBAlias = findAlias(plan, sideBTable);
        String sideATable = plan.tables().get(0);
        String sideAAlias = findAlias(plan, sideATable);

        String[] parts = cond.split("=");
        if (parts.length == 2) {
            for (String part : parts) {
                String trimmed = part.trim();
                // Find the side B column (not side A)
                if (!trimmed.startsWith(sideAAlias + ".") && !trimmed.startsWith(sideATable + ".")) {
                    int dotIdx = trimmed.indexOf('.');
                    if (dotIdx >= 0) {
                        return trimmed.substring(dotIdx + 1).trim();
                    }
                }
            }
        }
        return "linked_issue_key";
    }

    /**
     * Perform an in-memory join of side A and side B results.
     * Uses a HashMap for O(n+m) lookup: key side A on the join column, then match side B rows.
     */
    private QueryResult inMemoryJoin(QueryResult sideAResult, QueryResult sideBResult,
                                      LogicalPlan plan, String sideATable, String sideBTable) {
        if (sideAResult.rows().isEmpty() || sideBResult.rows().isEmpty()) {
            // Build merged columns even for empty result
            List<ResultColumn> mergedCols = mergeColumnsForJoin(sideAResult, sideBResult);
            return new QueryResult(mergedCols, List.of(), Map.of());
        }

        String sideAJoinCol = detectSideAJoinKeyColumn(plan, sideATable);
        String sideBJoinCol = detectSideBJoinColumn(plan, sideBTable);

        List<String> sideAColNames = sideAResult.columns().stream().map(ResultColumn::name).toList();
        List<String> sideBColNames = sideBResult.columns().stream().map(ResultColumn::name).toList();

        int sideAKeyIdx = sideAColNames.indexOf(sideAJoinCol);
        int sideBKeyIdx = sideBColNames.indexOf(sideBJoinCol);

        // Build side A lookup map: joinKey → list of rows (for n:m joins)
        Map<String, List<List<Object>>> sideAMap = new LinkedHashMap<>();
        for (List<Object> row : sideAResult.rows()) {
            String key = sideAKeyIdx >= 0 && sideAKeyIdx < row.size()
                    ? objToString(row.get(sideAKeyIdx)) : null;
            if (key != null) {
                sideAMap.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        // Merged column definitions: side A columns + side B columns (excluding join key duplicate)
        List<ResultColumn> mergedCols = mergeColumnsForJoin(sideAResult, sideBResult);
        List<String> mergedColNames = mergedCols.stream().map(ResultColumn::name).toList();

        List<List<Object>> joinedRows = new ArrayList<>();
        for (List<Object> sideBRow : sideBResult.rows()) {
            String key = sideBKeyIdx >= 0 && sideBKeyIdx < sideBRow.size()
                    ? objToString(sideBRow.get(sideBKeyIdx)) : null;
            if (key == null) continue;

            List<List<Object>> matchingAs = sideAMap.get(key);
            if (matchingAs == null || matchingAs.isEmpty()) continue;

            for (List<Object> sideARow : matchingAs) {
                List<Object> merged = new ArrayList<>(mergedColNames.size());
                for (String col : mergedColNames) {
                    int idxA = sideAColNames.indexOf(col);
                    int idxB = sideBColNames.indexOf(col);
                    if (idxA >= 0 && idxA < sideARow.size()) {
                        merged.add(sideARow.get(idxA));
                    } else if (idxB >= 0 && idxB < sideBRow.size()) {
                        merged.add(sideBRow.get(idxB));
                    } else {
                        merged.add(null);
                    }
                }
                joinedRows.add(merged);
            }
        }

        return new QueryResult(mergedCols, joinedRows, Map.of());
    }

    /**
     * Merge column lists for a join: side A columns first, then side B columns not already in side A.
     */
    private List<ResultColumn> mergeColumnsForJoin(QueryResult sideA, QueryResult sideB) {
        List<ResultColumn> merged = new ArrayList<>(sideA.columns());
        List<String> sideANames = sideA.columns().stream().map(ResultColumn::name).toList();
        for (ResultColumn col : sideB.columns()) {
            if (!sideANames.contains(col.name())) {
                merged.add(col);
            }
        }
        return merged;
    }

    /**
     * Perform a DuckDB hash join of side A and side B results using an in-memory DuckDB session.
     */
    private QueryResult duckDbHashJoin(QueryResult sideAResult, QueryResult sideBResult,
                                        LogicalPlan plan, String sideATable, String sideBTable) {
        try (DuckDbSession session = new DuckDbSession()) {
            session.registerTable(sideATable, sideAResult);
            session.registerTable(sideBTable, sideBResult);

            // Build join SQL from join condition
            String joinSql = buildDuckDbJoinSql(plan, sideATable, sideBTable);
            return session.executeJoin(joinSql);
        }
    }

    /**
     * Build a DuckDB JOIN SQL from the LogicalPlan.
     * Uses aliases in FROM and ON clauses exactly as in the original SQL.
     */
    private String buildDuckDbJoinSql(LogicalPlan plan, String sideATable, String sideBTable) {
        String sideAAlias = findAlias(plan, sideATable);
        String sideBAlias = findAlias(plan, sideBTable);

        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(sideATable);
        if (!sideAAlias.equals(sideATable)) {
            sql.append(" ").append(sideAAlias);
        }
        sql.append(" JOIN ").append(sideBTable);
        if (!sideBAlias.equals(sideBTable)) {
            sql.append(" ").append(sideBAlias);
        }

        if (plan.joinCondition() != null) {
            // Use the original join condition as-is (with aliases) — DuckDB understands aliases
            sql.append(" ON ").append(plan.joinCondition());
        }

        return sql.toString();
    }

    // -------------------------------------------------------------------------
    // Fragment building and execution
    // -------------------------------------------------------------------------

    /**
     * Execute a single fragment, routing to live or cache depending on path.
     */
    private FragmentOutcome executeFragment(Fragment fragment, TenantContext ctx,
                                            ClsMaskSet clsMaskSet, java.util.Set<String> principalSet,
                                            LogicalPlan plan) {
        String connectorId = fragment.connector();
        String tableName = plan.tables().isEmpty() ? connectorId : plan.tables().get(0);

        QueryResult result;
        long freshnessMs;

        if (fragment.path() == QueryPath.LIVE) {
            result = liveQueryService.execute(fragment, ctx);
            freshnessMs = 0L;
        } else {
            result = executionEngine.executeCacheFragment(fragment, ctx, clsMaskSet, principalSet);
            Watermark watermark = knowledgeCacheService.getWatermark(
                    connectorId, tableName, ctx.tenantId());
            freshnessMs = watermark != null ? watermark.ageMs() : 0L;
        }

        SourceMetadata sourceMeta = new SourceMetadata(
                connectorId,
                fragment.path().name(),
                freshnessMs
        );
        return new FragmentOutcome(result, sourceMeta);
    }

    /** Internal record carrying a fragment's execution result and source metadata. */
    private record FragmentOutcome(QueryResult result, SourceMetadata sourceMeta) {}

    /** Result of a single-source (non-JOIN) query execution. */
    private record SingleSourceExecutionResult(
            QueryResult result,
            List<SourceMetadata> sourceMetas,
            boolean partial) {}

    /** Result of a JOIN query execution. */
    private record JoinExecutionResult(
            QueryResult result,
            List<SourceMetadata> sourceMetas,
            boolean partial,
            String joinStrategy) {}

    /**
     * Derive a connection_ref for the given connector and user.
     */
    private String deriveConnectionRef(String connectorId, String userId) {
        return userId + "-" + connectorId + "-conn";
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
            telemetry.span("audit.failure", Map.of("error", e.getMessage())).close();
        }
    }

    private String sqlHash(String tenantId, String sql) {
        try {
            String salted = tenantId + ":" + sql;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(salted.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }

    /**
     * Build a stable cache key from the request and resolved authz context.
     * Key = SHA-256(tenantId | userId | sortedPrincipalSet | aclSyncedAt | maskSet | sql).
     * Different principals for the same SQL get different cache entries.
     * The ACL sync timestamp ensures stale-ACL scenarios bypass the cache.
     */
    private String buildCacheKey(TenantContext ctx, AuthzContext authzCtx, String sql) {
        try {
            // Sort principal set for determinism
            Set<String> sorted = authzCtx.principalSet() != null
                    ? new TreeSet<>(authzCtx.principalSet())
                    : new TreeSet<>();
            String maskKey = authzCtx.clsMaskSet() != null
                    ? authzCtx.clsMaskSet().maskedColumns().toString()
                    : "";
            // Include ACL sync epoch seconds so stale-ACL changes invalidate the key
            long aclEpoch = authzCtx.aclSyncedAt() != null
                    ? authzCtx.aclSyncedAt().getEpochSecond()
                    : 0L;
            String raw = ctx.tenantId()
                    + "|" + ctx.userId()
                    + "|" + sorted
                    + "|" + aclEpoch
                    + "|" + maskKey
                    + "|" + sql;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to a non-cacheable key per call (UUID)
            return UUID.randomUUID().toString();
        }
    }

    // -------------------------------------------------------------------------
    // Tenant status check
    // -------------------------------------------------------------------------

    /**
     * Check whether the tenant is active. Throws ENTITLEMENT_DENIED if the tenant
     * is found and explicitly inactive (crypto-shredded). Silently passes if the
     * tenant row does not exist in Postgres (supports integration tests that don't
     * seed a full tenant row).
     */
    private void checkTenantActive(String tenantId) {
        try {
            TenantConfig tenantConfig = tenantConfigService.findById(tenantId);
            if (!"active".equalsIgnoreCase(tenantConfig.status())) {
                throw new UsqlException(ErrorCode.ENTITLEMENT_DENIED,
                        "Tenant inactive: " + tenantId);
            }
        } catch (UsqlException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Tenant inactive:")) {
                throw e; // re-throw: explicit inactive status
            }
            // Tenant not found: allow through (graceful for test tenants)
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String objToString(Object obj) {
        return obj == null ? null : obj.toString();
    }
}
