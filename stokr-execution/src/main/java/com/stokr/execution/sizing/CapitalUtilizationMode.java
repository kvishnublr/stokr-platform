package com.stokr.execution.sizing;

public enum CapitalUtilizationMode {
    FULLY_ALLOCATED,
    DYNAMIC_UTILIZATION,
    RESERVED_BUFFER;

    public static CapitalUtilizationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULLY_ALLOCATED;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FULLY_ALLOCATED;
        }
    }
}
