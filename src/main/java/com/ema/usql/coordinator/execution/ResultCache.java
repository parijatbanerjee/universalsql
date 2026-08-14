package com.ema.usql.coordinator.execution;

import com.ema.usql.shared.QueryResult;

import java.util.Optional;

/**
 * Cache for query results, keyed by a deterministic string derived from
 * tenantId + userId + principalSet + aclVersion + maskSet + sql.
 *
 * <p>Implementations must be thread-safe.
 */
public interface ResultCache {

    /**
     * Look up a cached result for the given cache key.
     *
     * @param key SHA-256 cache key
     * @return cached result, or empty if not present
     */
    Optional<QueryResult> get(String key);

    /**
     * Store a query result in the cache.
     *
     * @param key    SHA-256 cache key
     * @param result the query result to cache
     */
    void put(String key, QueryResult result);
}
