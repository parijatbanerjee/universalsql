package com.ema.usql.controlplane;

import java.util.Map;

/**
 * Represents a tenant's core configuration loaded from the tenant and tenant_config tables.
 */
public record TenantConfig(
        String tenantId,
        String name,
        String kekId,
        String status,
        Map<String, String> config
) {
}
