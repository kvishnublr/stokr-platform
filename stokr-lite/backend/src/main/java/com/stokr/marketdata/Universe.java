package com.stokr.marketdata;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configurable universe of symbols to scan.
 * In production, this could come from database or admin config.
 */
@Component
public class Universe {

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

    public boolean contains(String symbol) {
        return DEFAULT_SYMBOLS.contains(symbol.toUpperCase());
    }
}
