package com.ema.usql.sourcegateway.api;

import java.time.Instant;

/**
 * Current rate limit state for a connector/tenant combination.
 */
public record RateLimitStatus(long remaining, long limit, Instant resetsAt) {
}
