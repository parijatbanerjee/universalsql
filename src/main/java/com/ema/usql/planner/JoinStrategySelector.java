package com.ema.usql.planner;

import com.ema.usql.shared.JoinStrategy;
import org.springframework.stereotype.Service;

/**
 * Selects the join strategy based on the number of rows on side A (the driving side).
 *
 * <p>Tier 0 (Semi-join reduction): If side A result count is below the ceiling,
 * extract join key values and push them as an IN-list to side B.
 *
 * <p>Tier 1 (DuckDB hash join): When side A has too many keys, fall back to
 * loading both sides into embedded DuckDB and joining in-process.
 */
@Service
public class JoinStrategySelector {

    /** Maximum number of side-A keys for semi-join reduction. */
    static final long SEMI_JOIN_KEY_CEILING = 100L;

    /**
     * Select the join strategy given the logical plan and side A row count.
     *
     * @param plan          the logical query plan (carries join condition)
     * @param sideARowCount number of rows returned from side A
     * @return SEMI_JOIN_REDUCTION if side A rows < ceiling, DUCKDB_HASH_JOIN otherwise
     */
    public JoinStrategy select(LogicalPlan plan, long sideARowCount) {
        if (sideARowCount < SEMI_JOIN_KEY_CEILING) {
            return JoinStrategy.SEMI_JOIN_REDUCTION;
        }
        return JoinStrategy.DUCKDB_HASH_JOIN;
    }
}
