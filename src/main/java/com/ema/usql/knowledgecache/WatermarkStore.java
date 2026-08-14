package com.ema.usql.knowledgecache;

import com.ema.usql.knowledgecache.api.Watermark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Reads and updates watermark records in the per-tenant DuckDB knowledge cache.
 */
public class WatermarkStore {

    private final TenantDuckDbRegistry registry;

    public WatermarkStore(TenantDuckDbRegistry registry) {
        this.registry = registry;
    }

    /**
     * Retrieve the watermark for the given source/table pair.
     * Returns null if no watermark exists yet.
     */
    public Watermark getWatermark(String source, String tableName, String tenantId) {
        Connection conn = registry.getConnection(tenantId);
        String sql = "SELECT source, table_name, last_synced_at, last_cursor FROM watermark " +
                     "WHERE source = ? AND table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String lastSyncedAtStr = rs.getString("last_synced_at");
                    Instant lastSyncedAt = lastSyncedAtStr != null
                            ? Instant.parse(lastSyncedAtStr)
                            : Instant.EPOCH;
                    return new Watermark(
                            rs.getString("source"),
                            rs.getString("table_name"),
                            lastSyncedAt,
                            rs.getString("last_cursor")
                    );
                }
                return null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read watermark for " + source + "/" + tableName, e);
        }
    }

    /**
     * Upsert the watermark for the given source/table pair.
     */
    public void updateWatermark(String source, String tableName, String tenantId,
                                Instant syncedAt, String cursor) {
        Connection conn = registry.getConnection(tenantId);
        String sql = """
                INSERT INTO watermark (source, table_name, last_synced_at, last_cursor)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (source, table_name) DO UPDATE SET
                    last_synced_at = excluded.last_synced_at,
                    last_cursor = excluded.last_cursor
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, tableName);
            ps.setString(3, syncedAt != null ? syncedAt.toString() : null);
            ps.setString(4, cursor);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update watermark for " + source + "/" + tableName, e);
        }
    }
}
