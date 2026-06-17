package com.stokr.strategy.lifecycle;

import java.util.Locale;
import java.util.Map;

/**
 * Config-driven per-strategy trade lifecycle profile.
 */
public record StrategyLifecycleProfile(
        int minHoldSeconds,
        boolean pressureExitEnabled,
        int timeStopMinutes,
        boolean allowReentry,
        int maxEntriesPerSymbolPerSession,
        // Profit-trailing (trend strategies only — it HURTS reversion strategies, which
        // exit better at their fixed target). Armed once the trade has run trailArmPct of
        // entry in your favour; exits when price gives back trailGiveBackPct (of entry)
        // from the peak (max-favorable-excursion). Lets trend tails run while locking gains.
        boolean profitTrailingEnabled,
        double trailArmPct,
        double trailGiveBackPct
) {
    /** Backward-compatible 5-arg constructor: profit-trailing OFF (existing strategies). */
    public StrategyLifecycleProfile(int minHoldSeconds, boolean pressureExitEnabled,
            int timeStopMinutes, boolean allowReentry, int maxEntriesPerSymbolPerSession) {
        this(minHoldSeconds, pressureExitEnabled, timeStopMinutes, allowReentry,
                maxEntriesPerSymbolPerSession, false, 0.0, 0.0);
    }

    // Max 3 entries per symbol per session: session replay showed re-entry churn (SBIN 6x,
    // HDFCBANK 5x in one day) repeatedly paying spread + charges on the same idea.
    private static final StrategyLifecycleProfile DEFAULT = new StrategyLifecycleProfile(
            300, true, 20, true, 3);

    private static final Map<String, StrategyLifecycleProfile> PROFILES = Map.ofEntries(
            Map.entry("VWAP_TRIPLE_CONFIRMATION",
                    new StrategyLifecycleProfile(600, true, 20, true, 3)),
            Map.entry("TRADE_BOOK_IMBALANCE",
                    new StrategyLifecycleProfile(120, true, 15, true, 2)),
            Map.entry("PRE_OPEN_GAP_OI",
                    new StrategyLifecycleProfile(60, true, 105, false, 1)),
            Map.entry("ORB_V",
                    new StrategyLifecycleProfile(0, true, 90, false, 1, true, 0.2, 0.5)),
            Map.entry("MORNING_SURGE",
                    new StrategyLifecycleProfile(300, true, 20, true, 2))
    );

    public static StrategyLifecycleProfile forStrategy(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return DEFAULT;
        }
        return PROFILES.getOrDefault(strategyKey.trim().toUpperCase(Locale.ROOT), DEFAULT);
    }
}
