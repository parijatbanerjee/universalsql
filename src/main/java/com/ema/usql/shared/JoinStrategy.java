package com.ema.usql.shared;

/**
 * The join strategy chosen by the planner for merging multi-source results.
 */
public enum JoinStrategy {
    /** Semi-join reduction: push a predicate list to the secondary source. */
    SEMI_JOIN_REDUCTION,
    /** Load both sides into embedded DuckDB and hash-join in process. */
    DUCKDB_HASH_JOIN,
    /** Result set too large to join; return an error. */
    QUERY_TOO_LARGE
}
