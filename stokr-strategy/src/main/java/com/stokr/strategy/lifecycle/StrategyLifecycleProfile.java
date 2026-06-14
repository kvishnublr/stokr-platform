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
            Map.entry("GAP_FILL", new StrategyLifecycleProfile(900, true, 20, false, 1)),
            Map.entry("SECTOR_LAGGARD", new StrategyLifecycleProfile(600, true, 20, true, 3)),
            Map.entry("ADV_CASH", new StrategyLifecycleProfile(480, true, 20, true, 3)),
            Map.entry("INDEX_HUNT", new StrategyLifecycleProfile(600, true, 45, true, 3)),
            Map.entry("NSE_SPIKE_DETECTION", new StrategyLifecycleProfile(0, true, 15, true, 3)),
            Map.entry("EARLY_BREAKOUT", new StrategyLifecycleProfile(300, true, 20, true, 3)),
            Map.entry("VWAP_BOUNCE", new StrategyLifecycleProfile(480, true, 20, true, 3)),
            Map.entry("S3_VWAP_RETEST", new StrategyLifecycleProfile(600, true, 20, true, 3)),
            Map.entry("S7_RANGE_FADE", new StrategyLifecycleProfile(600, true, 20, true, 3)),
            Map.entry("PRE_OPEN_GAP_OI", new StrategyLifecycleProfile(60, true, 105, false, 1)),
            Map.entry("USDINR_MOMENTUM", new StrategyLifecycleProfile(300, true, 20, true, 3)),
            Map.entry("EURINR_MEAN_REVERSION", new StrategyLifecycleProfile(300, true, 20, true, 3)),
            Map.entry("COMMODITIES_E2E_TEST", new StrategyLifecycleProfile(60, false, 30, true, 3)),
            // OPENING_RANGE_BREAKOUT — trend strategy: profit-trailing ON (arm at +0.2%,
            // give back 0.5% from peak), longer time stop, one entry per symbol per day.
            Map.entry("OPENING_RANGE_BREAKOUT",
                    new StrategyLifecycleProfile(0, true, 90, false, 1, true, 0.2, 0.5))
    );

    public static StrategyLifecycleProfile forStrategy(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return DEFAULT;
        }
        return PROFILES.getOrDefault(strategyKey.trim().toUpperCase(Locale.ROOT), DEFAULT);
    }
}
