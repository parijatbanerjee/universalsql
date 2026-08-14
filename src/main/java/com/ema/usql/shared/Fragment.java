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
        QueryPath path
) {
}
