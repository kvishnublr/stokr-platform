package com.stokr.strategy.service;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class InstrumentNormalizationService {

    public String normalizeForMarketData(String symbol) {
        if (symbol == null) {
            return null;
        }
        String trimmed = symbol.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        int colonIdx = upper.indexOf(':');
        if (colonIdx >= 0 && colonIdx + 1 < upper.length()) {
            return upper.substring(colonIdx + 1);
        }
        return upper;
    }
}
