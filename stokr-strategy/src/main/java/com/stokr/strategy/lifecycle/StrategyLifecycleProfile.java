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
        int maxEntriesPerSymbolPerSession
) {
    private static final StrategyLifecycleProfile DEFAULT = new StrategyLifecycleProfile(
            300, true, 20, true, 10);

    private static final Map<String, StrategyLifecycleProfile> PROFILES = Map.ofEntries(
            Map.entry("GAP_FILL", new StrategyLifecycleProfile(900, true, 20, false, 1)),
            Map.entry("SECTOR_LAGGARD", new StrategyLifecycleProfile(600, true, 20, true, 10)),
            Map.entry("ADV_CASH", new StrategyLifecycleProfile(480, true, 20, true, 10)),
            Map.entry("INDEX_HUNT", new StrategyLifecycleProfile(600, true, 45, true, 10)),
            Map.entry("NSE_SPIKE_DETECTION", new StrategyLifecycleProfile(0, true, 15, true, 10)),
            Map.entry("EARLY_BREAKOUT", new StrategyLifecycleProfile(300, true, 20, true, 10)),
            Map.entry("VWAP_BOUNCE", new StrategyLifecycleProfile(480, true, 20, true, 10)),
            Map.entry("S3_VWAP_RETEST", new StrategyLifecycleProfile(600, true, 20, true, 10)),
            Map.entry("S7_RANGE_FADE", new StrategyLifecycleProfile(600, true, 20, true, 10)),
            Map.entry("PRE_OPEN_GAP_OI", new StrategyLifecycleProfile(60, true, 105, false, 1)),
            Map.entry("USDINR_MOMENTUM", new StrategyLifecycleProfile(300, true, 20, true, 10)),
            Map.entry("EURINR_MEAN_REVERSION", new StrategyLifecycleProfile(300, true, 20, true, 10)),
            Map.entry("COMMODITIES_E2E_TEST", new StrategyLifecycleProfile(60, false, 30, true, 3))
    );

    public static StrategyLifecycleProfile forStrategy(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return DEFAULT;
        }
        return PROFILES.getOrDefault(strategyKey.trim().toUpperCase(Locale.ROOT), DEFAULT);
    }
}
