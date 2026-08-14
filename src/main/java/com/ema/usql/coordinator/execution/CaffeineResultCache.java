package com.ema.usql.coordinator.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ema.usql.shared.QueryResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Caffeine-backed in-process result cache with a 5-minute TTL.
 *
 * <p>The cache key is computed externally (SHA-256 of tenantId + userId + principalSet +
 * aclVersion + maskSet + sql) by {@link com.ema.usql.coordinator.Orchestrator}.
 *
 * <p>Cache hits skip the full execution pipeline and return the stored result directly.
 * Different principals for the same SQL get different cache entries because the principal
 * set is part of the key.
 */
@Component
public class CaffeineResultCache implements ResultCache {

    private static final int MAX_ENTRIES = 1_000;
    private static final Duration TTL = Duration.ofMinutes(5);

    private final Cache<String, QueryResult> cache;

    public CaffeineResultCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(TTL)
                .build();
    }

    @Override
    public Optional<QueryResult> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(String key, QueryResult result) {
        cache.put(key, result);
    }
}
