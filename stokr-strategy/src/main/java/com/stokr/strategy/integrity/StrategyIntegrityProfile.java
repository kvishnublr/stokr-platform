package com.stokr.strategy.integrity;

import java.util.Locale;
import java.util.Map;

/**
 * Per-strategy integrity requirements for fail-closed pre-evaluation.
 */
public record StrategyIntegrityProfile(
        boolean requiresNiftyOpeningSession,
        boolean requiresObiTicks,
        boolean checkEquityCandleFreshness,
        boolean checkIndexCandleFreshness
) {
    private static final Map<String, StrategyIntegrityProfile> PROFILES = Map.ofEntries(
            Map.entry("NSE_SPIKE_DETECTION", new StrategyIntegrityProfile(false, true, true, true)),
            Map.entry("GAP_FILL", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("SECTOR_LAGGARD", new StrategyIntegrityProfile(true, false, true, true)),
            Map.entry("EARLY_BREAKOUT", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("VWAP_BOUNCE", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("INDEX_HUNT", new StrategyIntegrityProfile(true, false, false, true)),
            Map.entry("ADV_CASH", new StrategyIntegrityProfile(false, true, true, false)),
            Map.entry("S3_VWAP_RETEST", new StrategyIntegrityProfile(false, false, false, true)),
            Map.entry("S7_RANGE_FADE", new StrategyIntegrityProfile(false, false, false, true)),
            Map.entry("PRE_OPEN_GAP_OI", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("USDINR_MOMENTUM", new StrategyIntegrityProfile(false, false, true, false)),
            Map.entry("EURINR_MEAN_REVERSION", new StrategyIntegrityProfile(false, false, true, false)),
            Map.entry("COMMODITIES_E2E_TEST", new StrategyIntegrityProfile(false, false, true, false))
    );

    private static final StrategyIntegrityProfile DEFAULT =
            new StrategyIntegrityProfile(false, false, true, false);

    public static StrategyIntegrityProfile forStrategy(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return DEFAULT;
        }
        return PROFILES.getOrDefault(strategyKey.trim().toUpperCase(Locale.ROOT), DEFAULT);
    }
}
