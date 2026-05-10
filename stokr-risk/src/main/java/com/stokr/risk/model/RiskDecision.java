package com.stokr.risk.model;

public record RiskDecision(boolean allowed, String reasonCode, String message) {

    public static RiskDecision ok() {
        return new RiskDecision(true, null, null);
    }

    public static RiskDecision reject(String reasonCode, String message) {
        return new RiskDecision(false, reasonCode, message);
    }
}
