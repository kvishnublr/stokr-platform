package com.stokr.admin.service;

import com.stokr.common.market.MarketSegmentUtil;

import java.util.Locale;

/**
 * Kite tradingsymbol helpers for Admin Test Signal Lab (EXCHANGE:SYMBOL format).
 * Exchange prefix must match the strategy segment ??? stale MCX hints must not tag NSE cash symbols.
 */
final class AdminTestSignalLabSymbol {

    private AdminTestSignalLabSymbol() {
    }

    static String normalize(String symbol, String exchangeHint) {
        return normalize(symbol, exchangeHint, null, null);
    }

    static String normalize(String symbol, String exchangeHint, String strategySegment, String strategyKey) {
        String expectedExchange = expectedExchange(strategySegment, strategyKey);
        if (symbol == null || symbol.isBlank()) {
            return defaultSymbolForExchange(expectedExchange);
        }
        String trimmed = symbol.trim().toUpperCase(Locale.ROOT);
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":", 2);
            String prefix = parts[0].trim();
            String bare = parts[1].trim();
            if (bare.isBlank()) {
                return defaultSymbolForExchange(expectedExchange);
            }
            if (!prefix.equals(expectedExchange)) {
                return expectedExchange + ":" + bare;
            }
            return prefix + ":" + bare;
        }

        String hint = (exchangeHint != null && !exchangeHint.isBlank())
                ? exchangeHint.trim().toUpperCase(Locale.ROOT)
                : expectedExchange;
        if (isNseCashExchange(expectedExchange)) {
            if ("MCX".equals(hint) || MarketSegmentUtil.isMcxSymbol(trimmed)) {
                hint = expectedExchange;
            }
        } else if ("MCX".equals(expectedExchange)) {
            hint = "MCX";
        }
        return hint + ":" + trimmed;
    }

    static String expectedExchange(String strategySegment, String strategyKey) {
        if (strategyKey != null && !strategyKey.isBlank()) {
            String key = strategyKey.trim().toUpperCase(Locale.ROOT);
            if (key.contains("MCX") || key.contains("COMMODIT")) {
                return "MCX";
            }
        }
        if (strategySegment != null && !strategySegment.isBlank()) {
            String segment = strategySegment.trim().toUpperCase(Locale.ROOT);
            if ("MCX".equals(segment)) {
                return "MCX";
            }
            if ("BSE".equals(segment)) {
                return "BSE";
            }
        }
        return "NSE";
    }

    static boolean exchangeMatchesStrategy(String normalizedSymbol, String strategySegment, String strategyKey) {
        if (normalizedSymbol == null || !normalizedSymbol.contains(":")) {
            return false;
        }
        String prefix = normalizedSymbol.substring(0, normalizedSymbol.indexOf(':')).toUpperCase(Locale.ROOT);
        return prefix.equals(expectedExchange(strategySegment, strategyKey));
    }

    private static boolean isNseCashExchange(String exchange) {
        return "NSE".equals(exchange) || "BSE".equals(exchange);
    }

    private static String defaultSymbolForExchange(String exchange) {
        if ("MCX".equals(exchange)) {
            return "MCX:CRUDEOIL";
        }
        if ("BSE".equals(exchange)) {
            return "BSE:ITC";
        }
        return "NSE:ITC";
    }
}
