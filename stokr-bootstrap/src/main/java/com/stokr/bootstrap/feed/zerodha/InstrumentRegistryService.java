package com.stokr.bootstrap.feed.zerodha;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory registry of Zerodha instrument_token ↔ trading symbol mappings.
 * Populated by {@link PlatformZerodhaLiveFeedRuntime} after it fetches the
 * NSE instruments dump on WebSocket connect. Shared with backfill services.
 */
@Service
public class InstrumentRegistryService {

    private volatile Map<Integer, String> tokenToSymbol = Map.of();
    private volatile Map<String, Integer> symbolToToken = Map.of();

    public void update(Map<Integer, String> tokenMap) {
        Map<String, Integer> reverse = new LinkedHashMap<>();
        tokenMap.forEach((token, symbol) -> reverse.put(symbol, token));
        this.tokenToSymbol = Collections.unmodifiableMap(new LinkedHashMap<>(tokenMap));
        this.symbolToToken = Collections.unmodifiableMap(reverse);
    }

    public Map<Integer, String> getTokenToSymbol() { return tokenToSymbol; }
    public Map<String, Integer> getSymbolToToken() { return symbolToToken; }

    public Integer getToken(String symbol) { return symbolToToken.get(symbol); }
    public String getSymbol(int token) { return tokenToSymbol.get(token); }
    public boolean isEmpty() { return tokenToSymbol.isEmpty(); }
    public int size() { return tokenToSymbol.size(); }
}
