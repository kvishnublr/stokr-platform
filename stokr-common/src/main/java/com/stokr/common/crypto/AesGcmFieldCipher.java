package com.stokr.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for column encryption; expects Base64 URL-encoded 32-byte key via configuration.
 */
public class AesGcmFieldCipher implements FieldCipher {

    private static final String AES = "AES";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmFieldCipher(byte[] rawKey) {
        if (rawKey.length != 32) {
            throw new IllegalArgumentException("Field cipher key must decode to 32 bytes (AES-256)");
        }
        this.secretKey = new SecretKeySpec(rawKey, AES);
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        byte[] all = Base64.getDecoder().decode(ciphertext);
        if (all.length < GCM_IV_LENGTH + 2) {
            throw new IllegalArgumentException("Invalid ciphertext");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(all, 0, iv, 0, GCM_IV_LENGTH);
        byte[] ct = new byte[all.length - GCM_IV_LENGTH];
        System.arraycopy(all, GCM_IV_LENGTH, ct, 0, ct.length);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Decrypt failed", e);
        }
    }
}
