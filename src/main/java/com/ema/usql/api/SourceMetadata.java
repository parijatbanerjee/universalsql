package com.ema.usql.api;

/**
 * Metadata about a single data source used to serve the query.
 */
public record SourceMetadata(
        String connector,
        String path,
        long freshnessMs
) {
}
