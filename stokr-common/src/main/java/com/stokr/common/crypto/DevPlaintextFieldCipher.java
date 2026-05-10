package com.stokr.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Non-production cipher — Base64 only; logs once at construction.
 */
public final class DevPlaintextFieldCipher implements FieldCipher {

    private static final Logger log = LoggerFactory.getLogger(DevPlaintextFieldCipher.class);

    public DevPlaintextFieldCipher() {
        log.warn("Using DevPlaintextFieldCipher — set stokr.crypto.field-key (Base64 32-byte) for AES encryption.");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return "DEV:" + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        if (!ciphertext.startsWith("DEV:")) {
            return ciphertext;
        }
        byte[] raw = Base64.getDecoder().decode(ciphertext.substring(4));
        return new String(raw, StandardCharsets.UTF_8);
    }
}
