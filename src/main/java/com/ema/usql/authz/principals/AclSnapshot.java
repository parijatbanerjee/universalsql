package com.ema.usql.authz.principals;

import java.time.Instant;

/**
 * A point-in-time snapshot of a user's ACL version and sync timestamp.
 * Used to detect stale ACL caches and enforce freshness requirements.
 */
public record AclSnapshot(
        String aclVersion,
        Instant syncedAt
) {
}
