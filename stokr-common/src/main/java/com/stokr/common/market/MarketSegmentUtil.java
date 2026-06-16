package com.stokr.common.market;

import java.util.List;
import java.util.Locale;

/**
 * Lightweight NSE vs MCX instrument detection for session and OMS gates.
 */
public final class MarketSegmentUtil {

    private static final List<String> MCX_PREFIXES = List.of(
            "GOLD", "SILVER", "CRUDEOIL", "NATURALGAS", "COPPER", "ZINC",
            "ALUMINIUM", "NICKEL", "LEAD", "COTTON", "CARDAMOM", "MENTHAOIL"
    );

    private MarketSegmentUtil() {
    }

    public static boolean isMcxSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("MCX:")) {
            return true;
        }
        int colon = s.indexOf(':');
        if (colon > 0 && "MCX".equals(s.substring(0, colon))) {
            return true;
        }
        String bare = colon > 0 ? s.substring(colon + 1) : s;
        for (String prefix : MCX_PREFIXES) {
            if (bare.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMcxContext(String symbol, String strategyKey) {
        if (isMcxSymbol(symbol)) {
            return true;
        }
        if (strategyKey == null || strategyKey.isBlank()) {
            return false;
        }
        String key = strategyKey.trim().toUpperCase(Locale.ROOT);
        return key.contains("MCX") || key.contains("COMMODIT");
    }
}
