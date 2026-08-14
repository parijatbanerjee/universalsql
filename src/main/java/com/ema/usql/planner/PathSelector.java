package com.ema.usql.planner;

import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryPath;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Pure function that selects the execution path for a fragment based on:
 * <ol>
 *   <li>ACL freshness (fail-closed: stale ACL forces LIVE regardless)</li>
 *   <li>Caller's freshness hint (includeLatestData flag)</li>
 *   <li>Watermark age vs max staleness</li>
 *   <li>Rate limit budget</li>
 *   <li>Estimated result set size</li>
 * </ol>
 *
 * <p>Decision tree (short-circuits in order):
 * <pre>
 * 1. aclAge > ACL_MAX_AGE               → LIVE        (fail-closed on entitlements)
 * 2. !hint.includeLatestData()           → CACHE
 * 3. wm.ageMs() <= hint.maxStalenessMs() → CACHE       (data fresh enough)
 * 4. budget.exhaustedFor(connector)      → CACHE_DEGRADED
 * 5. estimatedRows > LIVE_ROW_CEILING    → CACHE_DEGRADED
 * 6. default                             → LIVE
 * </pre>
 */
@Service
public class PathSelector {

    /** ACL sync timestamp older than this forces LIVE re-check. */
    static final Duration ACL_MAX_AGE = Duration.ofMinutes(5);

    /** Live queries are rejected if estimated rows exceed this ceiling. */
    static final long LIVE_ROW_CEILING = 10_000L;

    /**
     * Select the execution path for a fragment.
     *
     * @param fragment  the fragment being dispatched
     * @param hint      freshness requirements from the caller
     * @param watermark cache freshness for this connector/table (may be null)
     * @param budget    current rate limit budget snapshot
     * @param aclAge    freshness of the ACL snapshot for this user
     * @return the selected QueryPath
     */
    public QueryPath select(Fragment fragment,
                            FreshnessHint hint,
                            Watermark watermark,
                            RateLimitBudget budget,
                            AclFreshness aclAge) {

        // 1. Stale ACL → always LIVE (fail-closed on entitlements)
        if (aclAge.olderThan(ACL_MAX_AGE)) {
            return QueryPath.LIVE;
        }

        // 2. Caller does NOT want latest data → use cache
        if (!hint.includeLatestData()) {
            return QueryPath.CACHE;
        }

        // 3. Watermark is fresh enough → serve from cache
        if (watermark != null && watermark.ageMs() <= hint.maxStalenessMs()) {
            return QueryPath.CACHE;
        }

        // 4. Live budget exhausted → cache with degraded warning
        if (budget.exhaustedFor(fragment.connector())) {
            return QueryPath.CACHE_DEGRADED;
        }

        // 5. Too many rows → cache with degraded warning
        if (fragment.estimatedRows() > LIVE_ROW_CEILING) {
            return QueryPath.CACHE_DEGRADED;
        }

        // 6. All checks passed → execute live
        return QueryPath.LIVE;
    }
}
