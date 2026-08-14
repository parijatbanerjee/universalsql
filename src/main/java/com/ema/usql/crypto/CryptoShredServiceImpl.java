package com.ema.usql.crypto;

import com.ema.usql.crypto.api.CryptoShredService;
import com.ema.usql.crypto.api.KmsModule;
import org.springframework.stereotype.Service;

/**
 * Crypto-shred service implementation: destroys the tenant's KEK so all wrapped DEKs
 * become permanently inaccessible.
 *
 * <p>After {@link #shred(String)} is called:
 * <ul>
 *   <li>All calls to {@code kmsModule.unwrapDek(tenantId, ...)} will fail.</li>
 *   <li>Encrypted rows in the tenant's DuckDB file are permanently inaccessible,
 *       even if the file itself still exists on disk.</li>
 *   <li>This is the crypto-shred step; file deletion is cleanup performed separately
 *       by the AdminController.</li>
 * </ul>
 *
 * <p>Implements {@link CryptoShredService} interface from crypto.api so that
 * the controlplane module can invoke shredding without accessing crypto internals.
 */
@Service
public class CryptoShredServiceImpl implements CryptoShredService {

    private final KmsModule kmsModule;

    public CryptoShredServiceImpl(KmsModule kmsModule) {
        this.kmsModule = kmsModule;
    }

    /**
     * Destroy the KEK for the given tenant.
     * All wrapped DEKs stored in the tenant's DuckDB rows become permanently inaccessible
     * (crypto-shred effective immediately, before any file deletion).
     *
     * @param tenantId the tenant whose KEK should be destroyed
     */
    @Override
    public void shred(String tenantId) {
        kmsModule.destroyKek(tenantId);
    }
}
