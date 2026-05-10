package com.stokr.risk.model;

/**
 * Outcome of pre-live trading checks (trader, broker, strategy, platform arm).
 */
public record LiveTraderEligibilityResult(boolean allowed, String reasonCode, String message) {

    public static LiveTraderEligibilityResult ok() {
        return new LiveTraderEligibilityResult(true, null, null);
    }

    public static LiveTraderEligibilityResult reject(String reasonCode, String message) {
        return new LiveTraderEligibilityResult(false, reasonCode, message);
    }
}
