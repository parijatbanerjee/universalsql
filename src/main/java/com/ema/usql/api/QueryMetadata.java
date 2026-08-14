package com.ema.usql.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Metadata about how a query was executed and served.
 */
public record QueryMetadata(
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("freshness_ms") long freshnessMs,
        boolean partial,
        List<SourceMetadata> sources,
        @JsonProperty("rate_limit_status") Map<String, RateLimitInfo> rateLimitStatus,
        @JsonProperty("policy_applied") PolicyMetadata policyApplied,
        @JsonProperty("join_strategy") String joinStrategy
) {
}
