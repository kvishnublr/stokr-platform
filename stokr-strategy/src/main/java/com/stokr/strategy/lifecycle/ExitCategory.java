package com.stokr.strategy.lifecycle;

/**
 * Explicit exit categories ??? do not overload PRESSURE_EXIT for unrelated conditions.
 */
public enum ExitCategory {
    HARD_STOP,
    TARGET,
    PRESSURE_EXIT,
    TIME_EXIT,
    FEED_PROTECTION,
    LIQUIDITY_PROTECTION,
    MANUAL;

    public String outcomeStatus() {
        return switch (this) {
            case HARD_STOP -> "STOPLOSS_HIT";
            case TARGET -> "TARGET_HIT";
            case PRESSURE_EXIT -> "PRESSURE_EXIT";
            case TIME_EXIT -> "TIME_EXIT";
            case FEED_PROTECTION -> "FEED_PROTECTION";
            case LIQUIDITY_PROTECTION -> "LIQUIDITY_PROTECTION";
            case MANUAL -> "MANUAL";
        };
    }

    public static boolean isTerminalOutcome(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.toUpperCase()) {
            case "TARGET_HIT", "STOPLOSS_HIT", "SL_HIT", "BREAKEVEN_EXIT",
                 "EXPIRED", "PRESSURE_EXIT", "TIME_EXIT",
                 "FEED_PROTECTION", "LIQUIDITY_PROTECTION", "MANUAL" -> true;
            default -> false;
        };
    }
}
