package com.ema.usql.crypto.api;

import javax.crypto.SecretKey;

/**
 * Key Management Service interface (spec §7.1).
 * Only the crypto module, knowledgecache, and authz.principals may reference SecretKey.
 */
public interface KmsModule {

    /**
     * Generate a new DEK for the tenant and return it wrapped (encrypted) under the tenant's KEK.
     */
    WrappedDek generateDek(String tenantId, EncryptionContext ctx);

    /**
     * Unwrap (decrypt) a previously wrapped DEK using the tenant's KEK.
     * Returns the raw SecretKey — callers must not leak it outside the allowed modules.
     */
    SecretKey unwrapDek(String tenantId, WrappedDek wrapped, EncryptionContext ctx);

    /**
     * Destroy the Key Encryption Key for a tenant (e.g. on tenant off-boarding).
     * All wrapped DEKs become permanently inaccessible after this call.
     */
    void destroyKek(String tenantId);
}
