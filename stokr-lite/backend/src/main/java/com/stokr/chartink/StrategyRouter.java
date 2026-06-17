package com.stokr.chartink;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Routes Chartink scanner names to strategy identifiers.
 * Maps scanner names → strategy keys for execution and logging.
 */
@Slf4j
@Component
public class StrategyRouter {

    /**
     * Maps known Chartink scanner names to strategy identifiers.
     * Only the 5 core NSE intraday strategies.
     */
    private static final Map<String, String> SCANNER_TO_STRATEGY = Map.ofEntries(
            Map.entry("VWAP_TRIPLE_LONG", "VWAP_TRIPLE"),
            Map.entry("TRADE_BOOK_IMBALANCE", "TRADE_BOOK_IMBALANCE"),
            Map.entry("ORB_V_BREAKOUT", "ORB_V"),
            Map.entry("MORNING_SURGE_SHORT", "MORNING_SURGE"),
            Map.entry("PRE_OPEN_BUY", "PRE_OPEN")
    );

    /**
     * Maps scanner names to a default strategy ID.
     */
    private static final Map<String, Long> STRATEGY_IDS = Map.ofEntries(
            Map.entry("VWAP_TRIPLE_LONG", 1L),
            Map.entry("TRADE_BOOK_IMBALANCE", 2L),
            Map.entry("ORB_V_BREAKOUT", 3L),
            Map.entry("MORNING_SURGE_SHORT", 4L),
            Map.entry("PRE_OPEN_BUY", 5L)
    );

    public String resolveStrategyName(String scannerName) {
        if (scannerName == null) return "UNKNOWN";
        String key = scannerName.toUpperCase().replace(" ", "_");
        return SCANNER_TO_STRATEGY.getOrDefault(key, "UNKNOWN");
    }

    public Long resolveStrategyId(String scannerName) {
        if (scannerName == null) return 0L;
        String key = scannerName.toUpperCase().replace(" ", "_");
        return STRATEGY_IDS.getOrDefault(key, 0L);
    }

    public boolean isKnownScanner(String scannerName) {
        if (scannerName == null) return false;
        String key = scannerName.toUpperCase().replace(" ", "_");
        return SCANNER_TO_STRATEGY.containsKey(key);
    }
}
