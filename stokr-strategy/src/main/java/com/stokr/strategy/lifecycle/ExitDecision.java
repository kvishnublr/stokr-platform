package com.stokr.strategy.lifecycle;

/**
 * Structured exit decision from lifecycle engines.
 */
public record ExitDecision(
        ExitCategory category,
        String reason,
        PressureExitTrigger pressureTrigger,
        boolean minHoldBypassed
) {
    public static ExitDecision pressure(PressureExitTrigger trigger, String reason, boolean minHoldBypassed) {
        return new ExitDecision(ExitCategory.PRESSURE_EXIT, reason, trigger, minHoldBypassed);
    }

    public static ExitDecision emergency(ExitCategory category, String reason) {
        return new ExitDecision(category, reason, null, true);
    }

    public static ExitDecision timeExit(String reason) {
        return new ExitDecision(ExitCategory.TIME_EXIT, reason, null, false);
    }
}
