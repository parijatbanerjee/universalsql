package com.ema.usql.coordinator.execution;

import com.ema.usql.knowledgecache.api.KnowledgeCacheService;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Executes query fragments against the appropriate backend (cache or live connector).
 * For Task 9, only the cache path is implemented.
 */
@Service
public class ExecutionEngine {

    private final KnowledgeCacheService knowledgeCacheService;

    public ExecutionEngine(KnowledgeCacheService knowledgeCacheService) {
        this.knowledgeCacheService = knowledgeCacheService;
    }

    /**
     * Execute a fragment against the knowledge cache.
     */
    public QueryResult executeCacheFragment(Fragment fragment, TenantContext ctx) {
        return knowledgeCacheService.execute(fragment, ctx);
    }
}
