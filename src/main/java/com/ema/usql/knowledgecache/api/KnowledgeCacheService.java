package com.ema.usql.knowledgecache.api;

import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;

import java.util.List;

/**
 * Service for the per-tenant materialized DuckDB knowledge cache.
 * Stores encrypted records and serves cache-path queries.
 */
public interface KnowledgeCacheService {

    /**
     * Execute a fragment against the knowledge cache for the given tenant.
     */
    QueryResult execute(Fragment fragment, TenantContext tenantContext);

    /**
     * Write connector records into the knowledge cache for the given tenant.
     */
    void write(List<ConnectorRecord> records, TenantContext tenantContext);

    /**
     * Retrieve the current watermark for a connector/table pair.
     */
    Watermark getWatermark(String connector, String table, String tenantId);
}
