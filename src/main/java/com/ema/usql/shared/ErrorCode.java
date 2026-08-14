package com.ema.usql.shared;

/**
 * All error codes used in the Universal SQL layer (spec §8.1).
 * Never invent new codes; never throw raw RuntimeException across module boundaries.
 */
public enum ErrorCode {
    RATE_LIMIT_EXHAUSTED,
    STALE_DATA,
    ENTITLEMENT_DENIED,
    SOURCE_TIMEOUT,
    SOURCE_UNAVAILABLE,
    CONNECTION_REAUTH_REQUIRED,
    QUERY_TOO_LARGE,
    UNSUPPORTED_SQL
}
