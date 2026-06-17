package com.stokr.marketdata;

import com.stokr.strategy.UniverseGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configurable universe of symbols to scan.
 * Reads from database universe groups when available, falls back to defaults.
 */
@Component
@RequiredArgsConstructor
public class Universe {

    private final UniverseGroupService groupService;

    // NSE Top Liquid Stocks - default universe
    private static final List<String> DEFAULT_SYMBOLS = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK",
            "SBIN", "BHARTIARTL", "ITC", "KOTAKBANK", "LT",
            "HINDUNILVR", "AXISBANK", "MARUTI", "BAJFINANCE", "ASIANPAINT",
            "SUNPHARMA", "TITAN", "ULTRACEMCO", "WIPRO", "HCLTECH",
            "TATAMOTORS", "ONGC", "NTPC", "POWERGRID", "ADANIPORTS",
            "JSWSTEEL", "TATASTEEL", "COALINDIA", "M&M", "TECHM"
    );

    public List<String> getSymbols() {
        return DEFAULT_SYMBOLS;
    }

    public List<String> getSymbolsForGroup(String groupKey) {
        return groupService.findByKey(groupKey)
                .map(g -> groupService.resolveSymbolsForGroup(g.getId()))
                .orElse(DEFAULT_SYMBOLS);
    }

    public List<String> getSymbolsForGroupId(Long groupId) {
        return groupService.resolveSymbolsForGroup(groupId);
    }

    public boolean contains(String symbol) {
        return DEFAULT_SYMBOLS.contains(symbol.toUpperCase());
    }
}
