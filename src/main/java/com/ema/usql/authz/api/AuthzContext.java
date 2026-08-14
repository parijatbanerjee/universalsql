package com.ema.usql.authz.api;

import java.time.Instant;
import java.util.Set;

/**
 * The resolved authorization context for a single request.
 * Produced by AuthzService.resolve() and consumed by the planner to inject RLS/CLS.
 */
public record AuthzContext(
        Set<String> principalSet,
        RlsPredicate rlsPredicate,
        ClsMaskSet clsMaskSet,
        long aclVersion,
        Instant aclSyncedAt
) {
}
