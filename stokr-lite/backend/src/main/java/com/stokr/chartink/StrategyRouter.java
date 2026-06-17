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
     */
    private static final Map<String, String> SCANNER_TO_STRATEGY = Map.ofEntries(
            Map.entry("ORB_BREAKOUT", "ORB_MOMENTUM"),
            Map.entry("ORB_V_BREAKOUT", "ORB_V"),
            Map.entry("VWAP_DEVIATION", "VWAP_REVERSION"),
            Map.entry("VWAP_TRIPLE_LONG", "VWAP_TRIPLE"),
            Map.entry("TRADE_BOOK_IMBALANCE", "TRADE_BOOK_IMBALANCE"),
            Map.entry("PDH_BREAKOUT", "BREAKOUT_MOMENTUM"),
            Map.entry("PDL_BREAKOUT", "BREAKOUT_MOMENTUM"),
            Map.entry("VOLUME_SPIKE", "MOMENTUM_IGNITION"),
            Map.entry("BUYER_SELLER_IMBALANCE", "ORDER_FLOW_SPIKE"),
            Map.entry("OFI_PROXY", "ORDER_FLOW_SPIKE"),
            Map.entry("GAP_UP", "GAP_FILL_FADE"),
            Map.entry("GAP_DOWN", "GAP_FILL_FADE"),
            Map.entry("MORNING_SURGE_SHORT", "MORNING_SURGE"),
            Map.entry("PREOPEN_UNFILLED", "PREOPEN_DEMAND"),
            Map.entry("PREOPEN_IMBALANCE", "PREOPEN_IMBALANCE"),
            Map.entry("PRE_OPEN_BUY", "PRE_OPEN")
    );

    /**
     * Maps scanner names to a default strategy ID.
     * In production, this would query the strategies table.
     */
    private static final Map<String, Long> STRATEGY_IDS = Map.ofEntries(
            Map.entry("ORB_BREAKOUT", 1L),
            Map.entry("ORB_V_BREAKOUT", 1L),
            Map.entry("VWAP_DEVIATION", 2L),
            Map.entry("VWAP_TRIPLE_LONG", 2L),
            Map.entry("TRADE_BOOK_IMBALANCE", 3L),
            Map.entry("VOLUME_SPIKE", 4L),
            Map.entry("BUYER_SELLER_IMBALANCE", 5L),
            Map.entry("OFI_PROXY", 5L),
            Map.entry("GAP_UP", 6L),
            Map.entry("GAP_DOWN", 6L),
            Map.entry("MORNING_SURGE_SHORT", 7L),
            Map.entry("PREOPEN_UNFILLED", 8L),
            Map.entry("PREOPEN_IMBALANCE", 9L),
            Map.entry("PRE_OPEN_BUY", 10L)
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
