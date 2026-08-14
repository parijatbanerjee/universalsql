package com.ema.usql.planner;

import java.time.Duration;

/**
 * Captures how old the ACL snapshot is.
 * ACL staleness is checked BEFORE data staleness and overrides it.
 * Fail closed on entitlements — stale permissions force a live re-check.
 */
public interface AclFreshness {

    /**
     * Returns true if the ACL snapshot is older than the given threshold.
     * If true, the path selector must choose LIVE regardless of data freshness.
     */
    boolean olderThan(Duration threshold);
}
