package com.ema.usql.livequery.api;

import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.TenantContext;

/**
 * Service that executes a LIVE fragment against the source connector
 * (bypassing the DuckDB knowledge cache).
 *
 * <p>Freshness is always 0ms for live results (just fetched).
 * The call is bounded by {@link Fragment#timeoutMs()}.
 */
public interface LiveQueryService {

    /**
     * Execute the given fragment against the live source connector.
     *
     * @param fragment the fragment to execute (must have path=LIVE)
     * @param ctx the tenant/user context for rate limiting and token resolution
     * @return query result with freshness_ms=0
     * @throws com.ema.usql.shared.UsqlException with SOURCE_TIMEOUT if deadline exceeded
     */
    QueryResult execute(Fragment fragment, TenantContext ctx);
}
