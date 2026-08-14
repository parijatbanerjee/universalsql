package com.ema.usql.controlplane;

import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads tenant configuration from the tenant and tenant_config tables.
 * All DB access uses JdbcTemplate (constructor-injected).
 */
@Service
public class TenantConfigService {

    private final JdbcTemplate jdbc;

    public TenantConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Load the full TenantConfig for a given tenantId.
     *
     * @throws UsqlException with ENTITLEMENT_DENIED if tenant not found
     */
    public TenantConfig findById(String tenantId) {
        List<TenantConfig> rows = jdbc.query(
                "SELECT tenant_id, name, kek_id, status FROM tenant WHERE tenant_id = ?",
                (rs, rowNum) -> new TenantConfig(
                        rs.getString("tenant_id"),
                        rs.getString("name"),
                        rs.getString("kek_id"),
                        rs.getString("status"),
                        new HashMap<>()
                ),
                tenantId
        );

        if (rows.isEmpty()) {
            throw new UsqlException(ErrorCode.ENTITLEMENT_DENIED,
                    "Tenant not found: " + tenantId);
        }

        TenantConfig base = rows.get(0);

        // Load tenant_config key/value pairs
        Map<String, String> config = new HashMap<>();
        jdbc.query(
                "SELECT key, value FROM tenant_config WHERE tenant_id = ?",
                rs -> {
                    config.put(rs.getString("key"), rs.getString("value"));
                },
                tenantId
        );

        return new TenantConfig(
                base.tenantId(),
                base.name(),
                base.kekId(),
                base.status(),
                Map.copyOf(config)
        );
    }
}
