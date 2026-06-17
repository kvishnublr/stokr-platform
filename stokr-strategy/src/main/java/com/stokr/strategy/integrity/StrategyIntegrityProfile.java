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
            Map.entry("VWAP_TRIPLE_CONFIRMATION", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("TRADE_BOOK_IMBALANCE", new StrategyIntegrityProfile(false, true, true, false)),
            Map.entry("PRE_OPEN_GAP_OI", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("ORB_V", new StrategyIntegrityProfile(false, false, true, true)),
            Map.entry("MORNING_SURGE", new StrategyIntegrityProfile(false, false, true, true))
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
