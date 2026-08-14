package com.ema.usql.shared;

/**
 * Carries the tenant and user identity throughout the request lifecycle.
 * keyRef is a reference to the tenant's key encryption key — never the key itself.
 */
public record TenantContext(String tenantId, String userId, String keyRef) {
}
