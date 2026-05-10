package com.stokr.common.crypto;

/**
 * Encrypts sensitive fields at rest (broker tokens, refresh secrets).
 */
public interface FieldCipher {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
