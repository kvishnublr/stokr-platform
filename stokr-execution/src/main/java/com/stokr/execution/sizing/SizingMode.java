package com.stokr.execution.sizing;

public enum SizingMode {
    FIXED_QUANTITY,
    FIXED_CAPITAL_PER_TRADE,
    CAPITAL_BUCKET,
    RISK_BASED;

    public static SizingMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIXED_QUANTITY;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FIXED_QUANTITY;
        }
    }
}
