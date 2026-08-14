package com.ema.usql.planner;

/**
 * Snapshot of rate limit budget for the current tenant at plan time.
 * Used by the path selector to avoid live calls when the budget is exhausted.
 */
public interface RateLimitBudget {

    /**
     * Returns true if the rate limit budget for the given connector is exhausted.
     */
    boolean exhaustedFor(String connector);
}
