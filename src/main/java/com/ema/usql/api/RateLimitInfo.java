package com.ema.usql.api;

/**
 * Rate limit status for a connector.
 */
public record RateLimitInfo(
        long remaining,
        long resetAtEpochMs
) {
}
