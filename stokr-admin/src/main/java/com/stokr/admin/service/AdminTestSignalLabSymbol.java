package com.stokr.admin.service;

/**
 * Kite tradingsymbol helpers for Admin Test Signal Lab (NSE:SYMBOL format).
 */
final class AdminTestSignalLabSymbol {

    private AdminTestSignalLabSymbol() {
    }

    static String normalize(String symbol, String exchangeHint) {
        if (symbol == null || symbol.isBlank()) {
            return "NSE:ITC";
        }
        String trimmed = symbol.trim().toUpperCase();
        if (trimmed.contains(":")) {
            return trimmed;
        }
        String exchange = (exchangeHint != null && !exchangeHint.isBlank())
                ? exchangeHint.trim().toUpperCase()
                : "NSE";
        return exchange + ":" + trimmed;
    }
}
