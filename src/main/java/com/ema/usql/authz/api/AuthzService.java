package com.ema.usql.authz.api;

import com.ema.usql.shared.TenantContext;

/**
 * Authorization service: resolves the principal closure and generates RLS/CLS artifacts.
 * Must be called before planning — entitlements are enforced at plan time.
 */
public interface AuthzService {

    /**
     * Resolve the full authorization context for the given tenant/user.
     */
    AuthzContext resolve(TenantContext tenantContext);
}
