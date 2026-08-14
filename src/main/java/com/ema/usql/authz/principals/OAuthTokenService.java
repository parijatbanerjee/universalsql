package com.ema.usql.authz.principals;

import com.ema.usql.authz.api.TokenService;
import com.ema.usql.crypto.api.EncryptionContext;
import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.crypto.api.WrappedDek;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves OAuth bearer tokens from encrypted oauth_connection records.
 *
 * <p>This class is intentionally in authz.principals — the only non-crypto package
 * permitted to reference {@link SecretKey} (ArchUnit Rule 2).
 *
 * <p>Singleflight: if multiple threads concurrently request a token refresh for the
 * same connection_ref, only ONE refresh is performed. All other waiters block on
 * the in-flight CompletableFuture.
 */
@Service
public class OAuthTokenService implements TokenService {

    /** Tokens expiring within this window are proactively refreshed. */
    private static final int REFRESH_WINDOW_SECONDS = 30;

    /** AES/GCM constants — mirrors EnvelopeCipher (kept local to avoid cross-module import). */
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final OAuthConnectionStore store;
    private final KmsModule kmsModule;

    /** Singleflight map: one in-flight refresh per connection_ref. */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflightRefreshes =
            new ConcurrentHashMap<>();

    public OAuthTokenService(OAuthConnectionStore store, KmsModule kmsModule) {
        this.store = store;
        this.kmsModule = kmsModule;
    }

    @Override
    public String resolveToken(String connectionRef) {
        OAuthConnectionRecord record = store.findByRef(connectionRef)
                .orElseThrow(() -> new UsqlException(
                        ErrorCode.CONNECTION_REAUTH_REQUIRED,
                        "No oauth_connection found for ref: " + connectionRef));

        // Token still valid (not within the refresh window)?
        if (!isNearExpiry(record.expiresAt())) {
            return decryptToken(record);
        }

        // Token is expiring — singleflight pattern.
        // computeIfAbsent is atomic: only one thread will create the future and perform the refresh.
        // All other threads get the SAME future and block on join().
        CompletableFuture<String> leader = new CompletableFuture<>();
        CompletableFuture<String> inflight = inflightRefreshes.computeIfAbsent(connectionRef, k -> leader);

        if (inflight != leader) {
            // Another thread is already refreshing — wait for it
            try {
                return inflight.join();
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof UsqlException ue) throw ue;
                throw new UsqlException(ErrorCode.CONNECTION_REAUTH_REQUIRED,
                        "Token refresh failed for ref: " + connectionRef, cause);
            }
        }

        // We are the designated refresher
        try {
            String newToken = performRefresh(connectionRef, record);
            leader.complete(newToken);
            return newToken;
        } catch (UsqlException e) {
            leader.completeExceptionally(e);
            throw e;
        } catch (Exception e) {
            UsqlException wrapped = new UsqlException(
                    ErrorCode.CONNECTION_REAUTH_REQUIRED,
                    "Token refresh failed for ref: " + connectionRef, e);
            leader.completeExceptionally(wrapped);
            throw wrapped;
        } finally {
            // Only remove our own future (not a subsequent one)
            inflightRefreshes.remove(connectionRef, leader);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private boolean isNearExpiry(Instant expiresAt) {
        return expiresAt.isBefore(Instant.now().plusSeconds(REFRESH_WINDOW_SECONDS));
    }

    /**
     * Decrypt the wrapped token.
     * If wrappedDek is empty (seed / plaintext mode), the wrapped_token bytes are returned as-is.
     */
    String decryptToken(OAuthConnectionRecord record) {
        if (record.wrappedDek() == null || record.wrappedDek().length == 0) {
            // Plaintext mode: wrapped_token holds raw UTF-8 token bytes
            return new String(record.wrappedToken(), StandardCharsets.UTF_8);
        }

        try {
            EncryptionContext ctx = new EncryptionContext(record.tenantId(), "oauth-token");
            SecretKey dek = kmsModule.unwrapDek(record.tenantId(), new WrappedDek(record.wrappedDek()), ctx);
            byte[] plaintext = aesGcmDecrypt(record.wrappedToken(), dek, ctx);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (UsqlException e) {
            throw e;
        } catch (Exception e) {
            throw new UsqlException(ErrorCode.CONNECTION_REAUTH_REQUIRED,
                    "Failed to decrypt token for ref: " + record.connectionRef(), e);
        }
    }

    /**
     * Mock refresh: extend the expiry by 1 hour and return the same decrypted token.
     * In production this would call the OAuth provider's token endpoint.
     */
    protected String performRefresh(String connectionRef, OAuthConnectionRecord record) {
        String currentToken = decryptToken(record);
        Instant newExpiry = Instant.now().plusSeconds(3600);
        // Re-store with the new expiry (wrapped bytes unchanged for mock refresh)
        store.updateToken(connectionRef, record.wrappedToken(), record.wrappedDek(), newExpiry);
        return currentToken;
    }

    /**
     * AES/GCM decrypt — mirrors EnvelopeCipher logic but uses SecretKey directly.
     * Kept here (in authz.principals) so SecretKey never leaves the allowed packages.
     */
    private byte[] aesGcmDecrypt(byte[] ivAndCiphertext, SecretKey dek, EncryptionContext ctx)
            throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(ivAndCiphertext);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        cipher.updateAAD(aadBytes(ctx));
        return cipher.doFinal(ciphertext);
    }

    private byte[] aadBytes(EncryptionContext ctx) {
        return (ctx.tenantId() + ":" + ctx.purpose()).getBytes(StandardCharsets.UTF_8);
    }
}
