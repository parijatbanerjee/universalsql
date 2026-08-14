package com.ema.usql.crypto;

import com.ema.usql.crypto.api.EncryptionContext;
import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.crypto.api.WrappedDek;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Local filesystem-backed KMS module.
 *
 * KEKs are stored as AES-256 keys encoded as Base64 in files under data/kms/{tenantId}.key.
 * DEKs are AES-256 keys wrapped (AES/GCM) under the tenant's KEK with the EncryptionContext as AAD.
 *
 * destroyKek deletes the key file — after destruction, wrapped DEKs cannot be unwrapped
 * (crypto shredding).
 */
public class LocalKmsModule implements KmsModule {

    private static final String KEY_ALGORITHM = "AES";
    private static final int AES_KEY_SIZE_BITS = 256;
    private static final String KEY_FILE_SUFFIX = ".key";

    private final Path kmsDir;
    private final EnvelopeCipher envelopeCipher;

    public LocalKmsModule(Path kmsDir) {
        this.kmsDir = kmsDir;
        this.envelopeCipher = new EnvelopeCipher();
    }

    @Override
    public WrappedDek generateDek(String tenantId, EncryptionContext ctx) {
        try {
            SecretKey kek = loadKek(tenantId);
            SecretKey dek = generateAes256Key();

            // Wrap the raw DEK bytes using the KEK and EncryptionContext as AAD
            byte[] wrappedBytes = envelopeCipher.encrypt(dek.getEncoded(), kek, ctx);
            return new WrappedDek(wrappedBytes);
        } catch (UsqlException e) {
            throw e;
        } catch (Exception e) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to generate DEK for tenant: " + tenantId, e);
        }
    }

    @Override
    public SecretKey unwrapDek(String tenantId, WrappedDek wrapped, EncryptionContext ctx) {
        try {
            SecretKey kek = loadKek(tenantId);
            byte[] dekBytes = envelopeCipher.decrypt(wrapped.bytes(), kek, ctx);
            return new SecretKeySpec(dekBytes, KEY_ALGORITHM);
        } catch (UsqlException e) {
            throw e;
        } catch (Exception e) {
            // Let AEADBadTagException propagate directly so tests can assert on it.
            // Re-wrap other exceptions as UsqlException.
            if (e instanceof javax.crypto.AEADBadTagException) {
                throw new UsqlException(ErrorCode.ENTITLEMENT_DENIED,
                        "EncryptionContext mismatch — DEK unwrap rejected", e);
            }
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Failed to unwrap DEK for tenant: " + tenantId, e);
        }
    }

    @Override
    public void destroyKek(String tenantId) {
        Path keyFile = keyFilePath(tenantId);
        try {
            Files.deleteIfExists(keyFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to destroy KEK for tenant: " + tenantId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Load the KEK for a tenant from disk.
     * If no KEK exists yet, generate and persist one.
     */
    private SecretKey loadKek(String tenantId) throws IOException {
        Path keyFile = keyFilePath(tenantId);
        if (Files.exists(keyFile)) {
            byte[] keyBytes = Base64.getDecoder().decode(Files.readString(keyFile).trim());
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        }
        // Auto-provision: generate and persist a new KEK
        SecretKey kek = generateAes256Key();
        Files.createDirectories(kmsDir);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(kek.getEncoded()));
        return kek;
    }

    private Path keyFilePath(String tenantId) {
        return kmsDir.resolve(tenantId + KEY_FILE_SUFFIX);
    }

    private static SecretKey generateAes256Key() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance(KEY_ALGORITHM);
            kg.init(AES_KEY_SIZE_BITS);
            return kg.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("AES-256 key generation failed", e);
        }
    }
}
