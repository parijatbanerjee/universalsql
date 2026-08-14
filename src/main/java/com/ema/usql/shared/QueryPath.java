package com.ema.usql.shared;

/**
 * Execution path chosen by the path selector for a fragment.
 */
public enum QueryPath {
    /** Serve from the materialized DuckDB knowledge cache. */
    CACHE,
    /** Execute live against the source connector. */
    LIVE,
    /** Serve from cache with a staleness warning; live path is degraded. */
    CACHE_DEGRADED
}
