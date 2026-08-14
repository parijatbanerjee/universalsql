package com.ema.usql.knowledgecache.api;

import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;

import java.util.List;
import java.util.Set;

/**
 * Service for the per-tenant materialized DuckDB knowledge cache.
 * Stores encrypted records and serves cache-path queries.
 */
public interface KnowledgeCacheService {

    /**
     * Execute a fragment against the knowledge cache for the given tenant.
     * Applies CLS masking from the provided mask set and ACL filtering via principal set.
     */
    QueryResult execute(Fragment fragment, TenantContext tenantContext,
                        ClsMaskSet clsMaskSet, Set<String> principalSet);

    /**
     * Execute a fragment against the knowledge cache without CLS masking or ACL filtering.
     * Convenience method for backwards-compatible usage.
     */
    default QueryResult execute(Fragment fragment, TenantContext tenantContext) {
        return execute(fragment, tenantContext,
                new ClsMaskSet(java.util.Map.of()), java.util.Set.of());
    }

    /**
     * Write connector records into the knowledge cache for the given tenant.
     */
    void write(List<ConnectorRecord> records, TenantContext tenantContext);

    /**
     * Retrieve the current watermark for a connector/table pair.
     */
    Watermark getWatermark(String connector, String table, String tenantId);
}
