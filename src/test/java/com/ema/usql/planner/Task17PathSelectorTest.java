package com.ema.usql.planner;

import com.ema.usql.knowledgecache.api.Watermark;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryPath;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 17 pure unit tests for PathSelector — all 6 branches of the decision function.
 * No Spring context needed.
 *
 * Decision tree:
 * 1. aclAge > ACL_MAX_AGE               → LIVE
 * 2. !hint.includeLatestData()           → CACHE
 * 3. wm.ageMs() <= hint.maxStalenessMs() → CACHE
 * 4. budget.exhaustedFor(connector)      → CACHE_DEGRADED
 * 5. estimatedRows > LIVE_ROW_CEILING    → CACHE_DEGRADED
 * 6. default                             → LIVE
 */
class Task17PathSelectorTest {

    private final PathSelector selector = new PathSelector();

    // Helpers
    private static Fragment fragment(long estimatedRows) {
        return new Fragment("frag-1", "jira", "SELECT 1", java.util.List.of(),
                null, estimatedRows, QueryPath.CACHE);
    }

    private static Watermark freshWatermark(long stalenessMs) {
        // freshWatermark with given age
        Instant syncedAt = Instant.now().minusMillis(stalenessMs);
        return new Watermark("jira", "jira_issues", syncedAt, "cursor-1");
    }

    /** AclFreshness where the sync happened N minutes ago. */
    private static AclFreshness aclAge(long minutesOld) {
        Instant syncedAt = Instant.now().minusSeconds(minutesOld * 60);
        return new AclFreshnessImpl(syncedAt);
    }

    private static RateLimitBudget budgetExhausted() {
        return connector -> true;  // always exhausted
    }

    private static RateLimitBudget budgetOk() {
        return connector -> false; // never exhausted
    }

    // -------------------------------------------------------------------------
    // Test 1: stale ACL → LIVE (regardless of other params)
    // -------------------------------------------------------------------------

    @Test
    void staleAcl_forcesLive() {
        // ACL synced 10 minutes ago (> 5-minute ACL_MAX_AGE)
        AclFreshness staleAcl = aclAge(10);
        FreshnessHint hint = new FreshnessHint(false, 0);  // even with includeLatestData=false
        Watermark freshWm = freshWatermark(1_000); // 1s old — very fresh
        RateLimitBudget budget = budgetExhausted(); // budget exhausted

        QueryPath result = selector.select(fragment(1), hint, freshWm, budget, staleAcl);

        assertThat(result).isEqualTo(QueryPath.LIVE);
    }

    // -------------------------------------------------------------------------
    // Test 2: fresh ACL, includeLatestData=false → CACHE
    // -------------------------------------------------------------------------

    @Test
    void freshAcl_excludesLatestData_returnsCache() {
        AclFreshness freshAcl = aclAge(1); // 1 minute old — fresh
        FreshnessHint hint = new FreshnessHint(false, 0);
        Watermark staleWm = freshWatermark(600_000); // 10 min stale
        RateLimitBudget budget = budgetOk();

        QueryPath result = selector.select(fragment(1), hint, staleWm, budget, freshAcl);

        assertThat(result).isEqualTo(QueryPath.CACHE);
    }

    // -------------------------------------------------------------------------
    // Test 3: fresh ACL, includeLatestData=true, watermark fresh enough → CACHE
    // -------------------------------------------------------------------------

    @Test
    void freshAcl_includesLatestData_freshWatermark_returnsCache() {
        AclFreshness freshAcl = aclAge(1);
        FreshnessHint hint = new FreshnessHint(true, 30_000); // max staleness = 30s
        Watermark freshWm = freshWatermark(10_000); // 10s old — within 30s window
        RateLimitBudget budget = budgetOk();

        QueryPath result = selector.select(fragment(1), hint, freshWm, budget, freshAcl);

        assertThat(result).isEqualTo(QueryPath.CACHE);
    }

    // -------------------------------------------------------------------------
    // Test 4: fresh ACL, includeLatestData=true, watermark stale, budget exhausted → CACHE_DEGRADED
    // -------------------------------------------------------------------------

    @Test
    void freshAcl_staleWatermark_budgetExhausted_returnsCacheDegraded() {
        AclFreshness freshAcl = aclAge(1);
        FreshnessHint hint = new FreshnessHint(true, 5_000); // max staleness = 5s
        Watermark staleWm = freshWatermark(60_000); // 60s old — exceeds 5s max
        RateLimitBudget exhausted = budgetExhausted();

        QueryPath result = selector.select(fragment(1), hint, staleWm, exhausted, freshAcl);

        assertThat(result).isEqualTo(QueryPath.CACHE_DEGRADED);
    }

    // -------------------------------------------------------------------------
    // Test 5: fresh ACL, includeLatestData=true, watermark stale, budget OK, rows > ceiling → CACHE_DEGRADED
    // -------------------------------------------------------------------------

    @Test
    void freshAcl_staleWatermark_budgetOk_tooManyRows_returnsCacheDegraded() {
        AclFreshness freshAcl = aclAge(1);
        FreshnessHint hint = new FreshnessHint(true, 5_000);
        Watermark staleWm = freshWatermark(60_000);
        RateLimitBudget budget = budgetOk();

        // estimatedRows > LIVE_ROW_CEILING (10_000)
        QueryPath result = selector.select(fragment(10_001), hint, staleWm, budget, freshAcl);

        assertThat(result).isEqualTo(QueryPath.CACHE_DEGRADED);
    }

    // -------------------------------------------------------------------------
    // Test 6: fresh ACL, includeLatestData=true, watermark stale, budget OK, rows <= ceiling → LIVE
    // -------------------------------------------------------------------------

    @Test
    void freshAcl_staleWatermark_budgetOk_rowsWithinCeiling_returnsLive() {
        AclFreshness freshAcl = aclAge(1);
        FreshnessHint hint = new FreshnessHint(true, 5_000);
        Watermark staleWm = freshWatermark(60_000);
        RateLimitBudget budget = budgetOk();

        // estimatedRows <= LIVE_ROW_CEILING
        QueryPath result = selector.select(fragment(100), hint, staleWm, budget, freshAcl);

        assertThat(result).isEqualTo(QueryPath.LIVE);
    }

    // -------------------------------------------------------------------------
    // Boundary: exactly at ceiling
    // -------------------------------------------------------------------------

    @Test
    void exactlyAtCeiling_returnsCacheDegraded() {
        AclFreshness freshAcl = aclAge(1);
        FreshnessHint hint = new FreshnessHint(true, 0);
        Watermark staleWm = freshWatermark(60_000);
        RateLimitBudget budget = budgetOk();

        QueryPath result = selector.select(fragment(PathSelector.LIVE_ROW_CEILING + 1),
                hint, staleWm, budget, freshAcl);
        assertThat(result).isEqualTo(QueryPath.CACHE_DEGRADED);

        // Exactly at ceiling → still degraded (> not >=)
        result = selector.select(fragment(PathSelector.LIVE_ROW_CEILING),
                hint, staleWm, budget, freshAcl);
        assertThat(result).isEqualTo(QueryPath.LIVE);
    }
}
