package com.stokr.oms.util;

import java.util.Locale;

public final class OmsSymbolNormalizer {

    private OmsSymbolNormalizer() {
    }

    public static String normalize(String symbol) {
        if (symbol == null) {
            return "";
        }
        String t = symbol.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return t;
        }
        if (!t.contains(":") && t.matches("[A-Z0-9._-]+")) {
            return "NSE:" + t;
        }
        return t;
    }

    public static String display(String symbol) {
        if (symbol == null) {
            return "";
        }
        String t = symbol.trim();
        int idx = t.indexOf(':');
        return idx >= 0 ? t.substring(idx + 1) : t;
    }
}
