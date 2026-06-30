package com.stokr.broker;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * RFC 6238 TOTP (Time-based One-Time Password) generator.
 * Compatible with Google Authenticator and Zerodha 2FA.
 * No external dependencies — uses Java built-in HMAC-SHA1.
 */
public final class TotpUtils {

    private TotpUtils() {}

    /**
     * Generate the current 6-digit TOTP from a Base32-encoded secret.
     */
    public static String generate(String base32Secret) {
        byte[] key  = base32Decode(base32Secret.trim().toUpperCase().replace(" ", ""));
        long   T    = System.currentTimeMillis() / 1000L / 30;
        byte[] msg  = new byte[8];
        for (int i = 7; i >= 0; i--) { msg[i] = (byte)(T & 0xFF); T >>= 8; }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] hash   = mac.doFinal(msg);
            int    offset = hash[hash.length - 1] & 0x0F;
            int    code   = ((hash[offset]     & 0x7F) << 24)
                          | ((hash[offset + 1] & 0xFF) << 16)
                          | ((hash[offset + 2] & 0xFF) << 8)
                          |  (hash[offset + 3] & 0xFF);
            return String.format("%06d", code % 1_000_000);
        } catch (Exception e) {
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    /** Base32 decode (RFC 4648 — no padding required). */
    private static byte[] base32Decode(String s) {
        final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int outLen = s.length() * 5 / 8;
        byte[] out = new byte[outLen];
        int buf = 0, bitsLeft = 0, idx = 0;
        for (char c : s.toCharArray()) {
            int val = ALPHABET.indexOf(c);
            if (val < 0) continue;
            buf     = (buf << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte)(buf >> (bitsLeft - 8));
                bitsLeft  -= 8;
            }
        }
        return out;
    }
}
