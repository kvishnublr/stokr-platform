package com.stokr.strategy.signals;

/**
 * Separates system catalog scans from trader-owned and execution-mode streams.
 */
public enum SignalOwnerType {
    SYSTEM,
    USER,
    AUTO_TRADE,
    PAPER,
    LIVE;

    public static SignalOwnerType fromExecutionMode(String executionMode, boolean systemCatalogUser) {
        if (systemCatalogUser) {
            return SYSTEM;
        }
        if (executionMode == null || executionMode.isBlank()) {
            return USER;
        }
        return switch (executionMode.trim().toUpperCase()) {
            case "LIVE" -> LIVE;
            case "PAPER" -> PAPER;
            case "SIMULATED", "REPLAY" -> SYSTEM;
            default -> USER;
        };
    }
}
