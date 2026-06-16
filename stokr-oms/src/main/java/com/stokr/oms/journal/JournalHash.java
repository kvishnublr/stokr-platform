package com.stokr.oms.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class JournalHash {

    private JournalHash() {
    }

    public static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String chain(String prevChainHashOrNull, String payloadHash) {
        String base = prevChainHashOrNull == null ? payloadHash : prevChainHashOrNull + "|" + payloadHash;
        return sha256Hex(base);
    }
}
