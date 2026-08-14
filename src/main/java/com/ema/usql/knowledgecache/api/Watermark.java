package com.ema.usql.knowledgecache.api;

import java.time.Instant;

/**
 * Tracks the most recent sync position for a source table.
 * ageMs() returns how stale the materialized data is.
 */
public record Watermark(
        String source,
        String tableName,
        Instant lastSyncedAt,
        String lastCursor
) {
    /** Returns the age of the watermark in milliseconds. */
    public long ageMs() {
        return System.currentTimeMillis() - lastSyncedAt.toEpochMilli();
    }
}
