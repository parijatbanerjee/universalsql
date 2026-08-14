package com.ema.usql.crypto;

import com.ema.usql.crypto.api.EncryptionContext;
import com.ema.usql.crypto.api.KmsModule;
import com.ema.usql.crypto.api.WrappedDek;
import com.ema.usql.shared.UsqlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 4 acceptance tests for the crypto module.
 * No Spring context required — components are wired manually.
 */
class Task4CryptoTest {

    @TempDir
    Path tempDir;

    private KmsModule kms;
    private EnvelopeCipher cipher;

    @BeforeEach
    void setUp() {
        kms = new LocalKmsModule(tempDir);
        cipher = new EnvelopeCipher();
    }

    /**
     * Test 1: Generate DEK, encrypt data, decrypt data → same bytes.
     */
    @Test
    void roundTripEncryptDecrypt() throws Exception {
        EncryptionContext ctx = new EncryptionContext("tenant-test", "store");

        WrappedDek wrappedDek = kms.generateDek("tenant-test", ctx);
        SecretKey dek = kms.unwrapDek("tenant-test", wrappedDek, ctx);

        byte[] plaintext = "Hello, Universal SQL!".getBytes();
        byte[] ciphertext = cipher.encrypt(plaintext, dek, ctx);
        byte[] decrypted = cipher.decrypt(ciphertext, dek, ctx);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    /**
     * Test 2: Encrypt with ctx {tenant-A, store}, try to decrypt with ctx {tenant-B, store} → throws.
     */
    @Test
    void decryptWithMismatchedContextThrows() throws Exception {
        EncryptionContext ctxA = new EncryptionContext("tenant-A", "store");
        EncryptionContext ctxB = new EncryptionContext("tenant-B", "store");

        // Need a DEK — generate a fresh one for tenant-A
        WrappedDek wrapped = kms.generateDek("tenant-A", ctxA);
        SecretKey dek = kms.unwrapDek("tenant-A", wrapped, ctxA);

        byte[] plaintext = "sensitive data".getBytes();
        byte[] ciphertext = cipher.encrypt(plaintext, dek, ctxA);

        // Attempting to decrypt with the wrong context must fail (AEAD tag mismatch)
        assertThatThrownBy(() -> cipher.decrypt(ciphertext, dek, ctxB))
                .isInstanceOfAny(
                        javax.crypto.AEADBadTagException.class,
                        javax.crypto.BadPaddingException.class
                );
    }

    /**
     * Test 3: Generate DEK, wrap it, destroyKek, try unwrapDek → throws (key file gone).
     */
    @Test
    void unwrapAfterDestroyKekThrows() {
        EncryptionContext ctx = new EncryptionContext("tenant-destroy", "store");

        WrappedDek wrappedDek = kms.generateDek("tenant-destroy", ctx);

        // Sanity: unwrap works before destruction
        SecretKey dek = kms.unwrapDek("tenant-destroy", wrappedDek, ctx);
        assertThat(dek).isNotNull();

        // Destroy the KEK — key file is deleted
        kms.destroyKek("tenant-destroy");

        // Subsequent generateDek would create a NEW KEK — but unwrapDek with the OLD wrapped
        // DEK would succeed if we use a fresh KEK (wrong key).
        // To properly test crypto shredding: unwrapping with the new (wrong) KEK that gets
        // auto-created on the next loadKek call will produce an AEADBadTagException.
        // The spec says "fail if key file gone" — so we assert that a UsqlException is thrown
        // (wrapping AEADBadTagException from decrypting with the newly-provisioned wrong KEK).
        //
        // Note: the LocalKmsModule auto-provisions a new KEK on first access after deletion,
        // which means decryption fails with AEAD tag mismatch (different key used).
        assertThatThrownBy(() -> kms.unwrapDek("tenant-destroy", wrappedDek, ctx))
                .isInstanceOf(UsqlException.class)
                .hasMessageContaining("EncryptionContext mismatch");
    }

    /**
     * Test 4: Unwrapping a DEK with a mismatched EncryptionContext throws.
     * This verifies that the KmsModule itself enforces context binding.
     */
    @Test
    void unwrapDekWithMismatchedContextThrows() {
        EncryptionContext ctxWrap = new EncryptionContext("tenant-X", "purpose-A");
        EncryptionContext ctxUnwrap = new EncryptionContext("tenant-X", "purpose-B");

        WrappedDek wrappedDek = kms.generateDek("tenant-X", ctxWrap);

        // Unwrapping with a different purpose must fail
        assertThatThrownBy(() -> kms.unwrapDek("tenant-X", wrappedDek, ctxUnwrap))
                .isInstanceOf(UsqlException.class)
                .hasMessageContaining("EncryptionContext mismatch");
    }
}
