package com.ema.usql.crypto.api;

/**
 * An AES-GCM envelope-encrypted Data Encryption Key.
 * bytes is the ciphertext; it is safe to store and transmit.
 */
public record WrappedDek(byte[] bytes) {
}
