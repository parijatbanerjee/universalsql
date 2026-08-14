package com.ema.usql.crypto.api;

/**
 * Service for crypto-shredding a tenant's data by destroying the KEK.
 *
 * <p>After shredding, all wrapped DEKs for the tenant become permanently inaccessible,
 * rendering the encrypted rows in DuckDB unreadable even if the file still exists.
 *
 * <p>This interface lives in crypto.api so that controlplane can invoke it
 * without accessing crypto implementation internals.
 */
public interface CryptoShredService {

    /**
     * Destroy the KEK for the given tenant, making all encrypted data inaccessible.
     *
     * @param tenantId the tenant whose KEK should be destroyed
     */
    void shred(String tenantId);
}
