package com.ema.usql.planner;

/**
 * Carries the caller's freshness requirements into the path selector.
 * includeLatestData: if true, the caller wants live or near-live data.
 * maxStalenessMs: the maximum acceptable age of cached data in milliseconds.
 */
public record FreshnessHint(boolean includeLatestData, long maxStalenessMs) {

    /** Returns true if the caller does NOT require the latest data. */
    public boolean excludesLatestData() {
        return !includeLatestData;
    }
}
