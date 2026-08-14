package com.ema.usql.controlplane;

/**
 * Represents a source connector's catalog entry loaded from the source_catalog table.
 * columns and capabilities are stored as raw JSON strings (JSONB columns).
 */
public record SourceCatalogEntry(
        String connectorId,
        String tableName,
        String columns,
        String capabilities
) {
}
