package com.ema.usql.coordinator.execution;

import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Executes query fragments against the appropriate backend (cache or live connector).
 * Passes CLS masking and ACL principal sets through to the cache service.
 */
@Service
public class ExecutionEngine {

    private final KnowledgeCacheService knowledgeCacheService;

    public ExecutionEngine(KnowledgeCacheService knowledgeCacheService) {
        this.knowledgeCacheService = knowledgeCacheService;
    }

    /**
     * Execute a fragment against the knowledge cache with CLS masking and ACL filtering.
     */
    public QueryResult executeCacheFragment(Fragment fragment, TenantContext ctx,
                                            ClsMaskSet clsMaskSet, Set<String> principalSet) {
        return knowledgeCacheService.execute(fragment, ctx, clsMaskSet, principalSet);
    }

    /**
     * Execute a fragment against the knowledge cache without CLS/ACL enforcement.
     * Kept for backward compatibility with tests that use addFilters = false.
     */
    public QueryResult executeCacheFragment(Fragment fragment, TenantContext ctx) {
        return knowledgeCacheService.execute(fragment, ctx);
    }
}
