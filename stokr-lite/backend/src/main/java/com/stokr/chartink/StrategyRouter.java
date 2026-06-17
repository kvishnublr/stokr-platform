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
            Map.entry("VWAP_DEVIATION", "VWAP_REVERSION"),
            Map.entry("PDH_BREAKOUT", "BREAKOUT_MOMENTUM"),
            Map.entry("PDL_BREAKOUT", "BREAKOUT_MOMENTUM"),
            Map.entry("VOLUME_SPIKE", "MOMENTUM_IGNITION"),
            Map.entry("BUYER_SELLER_IMBALANCE", "ORDER_FLOW_SPIKE"),
            Map.entry("OFI_PROXY", "ORDER_FLOW_SPIKE"),
            Map.entry("GAP_UP", "GAP_FILL_FADE"),
            Map.entry("GAP_DOWN", "GAP_FILL_FADE"),
            Map.entry("PREOPEN_UNFILLED", "PREOPEN_DEMAND"),
            Map.entry("PREOPEN_IMBALANCE", "PREOPEN_IMBALANCE")
    );

    /**
     * Maps scanner names to a default strategy ID.
     * In production, this would query the strategies table.
     */
    private static final Map<String, Long> STRATEGY_IDS = Map.ofEntries(
            Map.entry("ORB_BREAKOUT", 1L),
            Map.entry("VWAP_DEVIATION", 2L),
            Map.entry("VOLUME_SPIKE", 3L),
            Map.entry("BUYER_SELLER_IMBALANCE", 4L),
            Map.entry("OFI_PROXY", 4L),
            Map.entry("GAP_UP", 5L),
            Map.entry("GAP_DOWN", 5L),
            Map.entry("PREOPEN_UNFILLED", 6L),
            Map.entry("PREOPEN_IMBALANCE", 7L)
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
