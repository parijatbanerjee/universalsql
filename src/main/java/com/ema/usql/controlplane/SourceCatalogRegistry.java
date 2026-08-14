package com.ema.usql.controlplane;

import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reads connector/source catalog entries from the source_catalog table.
 * All DB access uses JdbcTemplate (constructor-injected).
 */
@Service
public class SourceCatalogRegistry {

    private final JdbcTemplate jdbc;

    public SourceCatalogRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Load the catalog entry for a given connectorId.
     *
     * @throws UsqlException with SOURCE_UNAVAILABLE if the connector is not registered
     */
    public SourceCatalogEntry findByConnector(String connectorId) {
        List<SourceCatalogEntry> rows = jdbc.query(
                "SELECT connector_id, table_name, column_json::text, capability_json::text " +
                "FROM source_catalog WHERE connector_id = ?",
                (rs, rowNum) -> new SourceCatalogEntry(
                        rs.getString("connector_id"),
                        rs.getString("table_name"),
                        rs.getString("column_json"),
                        rs.getString("capability_json")
                ),
                connectorId
        );

        if (rows.isEmpty()) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "No source catalog entry for connector: " + connectorId);
        }

        return rows.get(0);
    }
}
