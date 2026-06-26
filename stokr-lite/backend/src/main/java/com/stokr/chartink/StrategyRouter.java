package com.stokr.chartink;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class StrategyRouter {

    private static final Map<String, String> SCANNER_TO_STRATEGY = Map.ofEntries(
            Map.entry("STOKR_MORNING_SURGE_SHORT", "MORNING_SURGE_REVERSAL"),
            Map.entry("MORNING_SURGE_SHORT", "MORNING_SURGE_REVERSAL")
    );

    private static final Map<String, Long> STRATEGY_IDS = Map.ofEntries(
            Map.entry("STOKR_MORNING_SURGE_SHORT", 4L),
            Map.entry("MORNING_SURGE_SHORT", 4L)
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
