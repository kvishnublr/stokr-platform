package com.stokr.marketdata.tick;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
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

    @PostConstruct
    public void init() {
        try {
            refresh();
            log.info("Loaded {} NFO instrument tokens", symbolToToken.size());
        } catch (Exception e) {
            log.warn("Failed to load instruments on startup: {}", e.getMessage());
        }
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
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(
                        new java.io.ByteArrayInputStream(resp.getBody()))))) {
                reader.readLine(); // skip header
                String line;
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
                }
            }
            symbolToToken.clear();
            symbolToToken.putAll(newMap);
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
}
