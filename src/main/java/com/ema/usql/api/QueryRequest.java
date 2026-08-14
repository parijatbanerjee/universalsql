package com.ema.usql.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Incoming query request from the API client.
 */
public record QueryRequest(
        String sql,
        @JsonProperty("include_latest_data") boolean includeLatestData,
        @JsonProperty("max_staleness_ms") long maxStalenessMs,
        @JsonProperty("timeout_ms") long timeoutMs
) {
}
