package com.ema.usql.api;

/**
 * Information about which security policies were applied to the query result.
 */
public record PolicyMetadata(
        boolean rlsApplied,
        boolean clsApplied,
        String rlsExpression
) {
}
