package com.ema.usql.planner;

import java.time.Duration;
import java.time.Instant;

/**
 * Default implementation of AclFreshness based on the sync timestamp from AuthzContext.
 */
public class AclFreshnessImpl implements AclFreshness {

    private final Instant syncedAt;

    public AclFreshnessImpl(Instant syncedAt) {
        this.syncedAt = syncedAt != null ? syncedAt : Instant.EPOCH;
    }

    @Override
    public boolean olderThan(Duration threshold) {
        Duration age = Duration.between(syncedAt, Instant.now());
        return age.compareTo(threshold) > 0;
    }
}
