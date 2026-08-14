package com.ema.usql.crypto;

import com.ema.usql.crypto.api.EncryptionContext;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * Performs AES/GCM/NoPadding envelope encryption using a provided DEK.
 * The EncryptionContext is bound as Additional Authenticated Data (AAD)
 * to prevent cross-context key misuse.
 *
 * Wire format: [12-byte IV][ciphertext+tag]
 */
class EnvelopeCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypt plaintext using the given DEK. The EncryptionContext is bound as AAD.
     *
     * @return IV + ciphertext bytes
     */
    byte[] encrypt(byte[] plaintext, SecretKey dek, EncryptionContext ctx) throws Exception {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        cipher.updateAAD(aadBytes(ctx));
        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH_BYTES + ciphertext.length);
        buf.put(iv);
        buf.put(ciphertext);
        return buf.array();
    }

    /**
     * Decrypt ciphertext (IV + ciphertext bytes) using the given DEK. Verifies the AAD.
     *
     * @throws javax.crypto.AEADBadTagException if the EncryptionContext does not match
     */
    byte[] decrypt(byte[] ivAndCiphertext, SecretKey dek, EncryptionContext ctx) throws Exception {
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

    /**
     * Serialises EncryptionContext into bytes used as AAD.
     * Format: "tenantId:purpose" as UTF-8 bytes.
     */
    private byte[] aadBytes(EncryptionContext ctx) {
        return (ctx.tenantId() + ":" + ctx.purpose()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
