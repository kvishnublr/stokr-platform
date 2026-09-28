package com.stokr.marketdata.tick;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class KiteInstrumentTokenCache {

    private static final String INSTRUMENTS_URL = "https://api.kite.trade/instruments";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ConcurrentHashMap<String, Integer> symbolToToken = new ConcurrentHashMap<>();
    /** NFO tradingsymbol → NSE exchange token (the exchangeInstrumentID XTS brokers expect). */
    private final ConcurrentHashMap<String, Long> symbolToExchangeToken = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs = 0;

    @PostConstruct
    public void init() {
        try {
            refresh();
            log.info("Loaded {} NFO instrument tokens", symbolToToken.size());
        } catch (Exception e) {
            log.warn("Failed to load instruments on startup: {}", e.getMessage());
        }
    }

    /** New weekly contracts are listed every trading day; reload before the open. */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyRefresh() {
        refresh();
        log.info("Daily instrument refresh: {} NFO tokens", symbolToToken.size());
    }

    public void refresh() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
            headers.set("Accept-Encoding", "gzip");
            headers.set("Authorization", "token " + System.getenv().getOrDefault("ZERODHA_API_KEY", ""));

            ResponseEntity<byte[]> resp = restTemplate.exchange(
                INSTRUMENTS_URL, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

            if (resp.getBody() == null) return;

            ConcurrentHashMap<String, Integer> newMap = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Long> newExchangeTokens = new ConcurrentHashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(
                        new java.io.ByteArrayInputStream(resp.getBody()))))) {
                reader.readLine(); // skip header
                String line;
                // Cols: instrument_token, exchange_token, tradingsymbol, name, last_price, expiry,
                //       strike, tick_size, lot_size, instrument_type, segment, exchange
                while ((line = reader.readLine()) != null) {
                    String[] cols = line.split(",");
                    if (cols.length < 12) continue;
                    String exchange = cols[11];
                    if (!"NFO".equals(exchange)) continue;
                    int token;
                    try {
                        token = Integer.parseInt(cols[0]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    String symbol = cols[2];
                    newMap.put(symbol, token);
                    try {
                        newExchangeTokens.put(symbol, Long.parseLong(cols[1]));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (!newMap.isEmpty()) {
                symbolToToken.clear();
                symbolToToken.putAll(newMap);
                symbolToExchangeToken.clear();
                symbolToExchangeToken.putAll(newExchangeTokens);
                lastRefreshMs = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log.error("Failed to refresh instruments: {}", e.getMessage());
        }
    }

    public Integer getToken(String nfoSymbol) {
        return symbolToToken.get(nfoSymbol);
    }

    public Map<String, Integer> getTokens(List<String> symbols) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String s : symbols) {
            Integer t = symbolToToken.get(s);
            if (t != null) result.put(s, t);
        }
        return result;
    }

    public int size() {
        return symbolToToken.size();
    }

    /**
     * NSE exchange token for an NFO tradingsymbol (e.g. NIFTY2692923400CE). A miss triggers at most
     * one reload per 10 minutes, so contracts listed after the last refresh are still found.
     */
    public Long getExchangeToken(String nfoSymbol) {
        if (nfoSymbol == null) return null;
        Long t = symbolToExchangeToken.get(nfoSymbol.toUpperCase());
        if (t == null && System.currentTimeMillis() - lastRefreshMs > 10 * 60 * 1000) {
            lastRefreshMs = System.currentTimeMillis();
            refresh();
            t = symbolToExchangeToken.get(nfoSymbol.toUpperCase());
        }
        return t;
    }
}
