package com.ema.usql.shared;

import java.util.List;

/**
 * A single-source unit of work produced by the planner.
 * The coordinator dispatches one fragment per source connector.
 */
public record Fragment(
        String fragmentId,
        String connector,
        String sql,
        List<String> predicates,
        String connectionRef,
        long estimatedRows,
        QueryPath path,
        long timeoutMs
) {
    /** Backward-compatible constructor: defaults timeoutMs to 30 seconds. */
    public Fragment(String fragmentId, String connector, String sql,
                    List<String> predicates, String connectionRef,
                    long estimatedRows, QueryPath path) {
        this(fragmentId, connector, sql, predicates, connectionRef, estimatedRows, path, 30_000L);
    }
}
