package com.ema.usql.crypto.api;

/**
 * Additional authenticated data passed to KMS operations to bind the key
 * to a specific tenant and purpose.
 */
public record EncryptionContext(String tenantId, String purpose) {
}
