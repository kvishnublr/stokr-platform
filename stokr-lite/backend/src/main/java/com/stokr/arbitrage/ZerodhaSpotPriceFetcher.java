package com.stokr.arbitrage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ZerodhaSpotPriceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaSpotPriceFetcher.class);

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${broker.zerodha.api-key:${zerodha.api-key:}}")
    private String apiKey;

    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 2000;

    public ZerodhaSpotPriceFetcher(ZerodhaTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public double getSpotPrice(String instrumentKey) {
        long now = System.currentTimeMillis();
        Long cachedTime = cacheTimestamps.get(instrumentKey);
        Double cached = cache.get(instrumentKey);
        if (cached != null && cachedTime != null && (now - cachedTime) < CACHE_TTL_MS) {
            return cached;
        }

        double[] res = getSpotAndFutures(instrumentKey, instrumentKey);
        return (res != null && res.length > 0) ? res[0] : 0;
    }

    public String getAuthToken() {
        try {
            var auth = tokenManager.getCurrentAuth();
            if (auth != null && auth.getAccessToken() != null) {
                return auth.getAccessToken();
            }
        } catch (Exception e) {
            log.warn("Failed to get token: {}", e.getMessage());
        }
        return null;
    }

    public double[] getSpotAndFutures(String spotKey, String futuresKey) {
        long now = System.currentTimeMillis();
        Double spotCached = cache.get(spotKey);
        Long spotTime = cacheTimestamps.get(spotKey);
        Double futCached = cache.get(futuresKey);
        Long futTime = cacheTimestamps.get(futuresKey);

        boolean spotFresh = spotCached != null && spotTime != null && (now - spotTime) < CACHE_TTL_MS;
        boolean futFresh = futCached != null && futTime != null && (now - futTime) < CACHE_TTL_MS;

        if (spotFresh && futFresh) {
            return new double[]{spotCached, futCached};
        }

        try {
            String token = getAuthToken();
            if (token == null) {
                log.warn("Zerodha auth token unavailable for quote fetch — spot/futures will be 0");
                return new double[]{0, 0};
            }

            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Zerodha API key not configured (broker.zerodha.api-key) — quote fetch will fail");
                return new double[]{0, 0};
            }

            String urlStr = "https://api.kite.trade/quote?i=" + spotKey.replace(" ", "%20") + "&i=" + futuresKey.replace(" ", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            double spot = 0, fut = 0;

            if (data.isObject()) {
                spot = extractPrice(data, spotKey);
                // Index instruments sometimes fail under one key form — try alternates
                if (spot <= 0) {
                    for (String alt : alternateSpotKeys(spotKey)) {
                        spot = extractPrice(data, alt);
                        if (spot > 0) break;
                    }
                }

                fut = extractPrice(data, futuresKey);
                if (fut <= 0) {
                    fut = extractPrice(data, futuresKey.replace(" ", "%20"));
                }

                if (spot > 0) {
                    cache.put(spotKey, spot);
                    cacheTimestamps.put(spotKey, now);
                }
                if (fut > 0) {
                    cache.put(futuresKey, fut);
                    cacheTimestamps.put(futuresKey, now);
                }
            }

            log.info("SpotFetcher result for {} / {}: spot={}, fut={}", spotKey, futuresKey, spot, fut);
            if (spot <= 0) {
                for (String alt : alternateSpotKeys(spotKey)) {
                    double[] retry = fetchPair(alt, futuresKey, token, now);
                    if (retry[0] > 0) {
                        spot = retry[0];
                        if (retry[1] > 0) fut = retry[1];
                        cache.put(spotKey, spot);
                        cacheTimestamps.put(spotKey, now);
                        log.info("SpotFetcher recovered spot via {}: {}", alt, spot);
                        break;
                    }
                }
            }
            return new double[]{spot, fut};

        } catch (Exception e) {
            log.error("Failed batch fetch for {} / {}: {}", spotKey, futuresKey, e.getMessage());
        }
        return new double[]{0, 0};
    }

    private double[] fetchPair(String spotKey, String futuresKey, String token, long now) {
        try {
            String urlStr = "https://api.kite.trade/quote?i=" + spotKey.replace(" ", "%20") + "&i=" + futuresKey.replace(" ", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");
            ResponseEntity<String> response = restTemplate.exchange(
                    urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode data = mapper.readTree(response.getBody()).path("data");
            double spot = extractPrice(data, spotKey);
            double fut = extractPrice(data, futuresKey);
            if (fut > 0) {
                cache.put(futuresKey, fut);
                cacheTimestamps.put(futuresKey, now);
            }
            return new double[]{spot, fut};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    private double extractPrice(JsonNode data, String key) {
        if (key == null || key.isBlank()) return 0;
        JsonNode node = data.path(key);
        if (node.isMissingNode()) node = data.path(key.replace(" ", "%20"));
        if (node.isMissingNode()) {
            // Match by iterating keys (Kite may normalize whitespace)
            var fields = data.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (e.getKey().equalsIgnoreCase(key) || e.getKey().replace("%20", " ").equalsIgnoreCase(key)) {
                    node = e.getValue();
                    break;
                }
            }
        }
        if (node == null || node.isMissingNode()) return 0;
        double last = node.path("last_price").asDouble(0);
        if (last > 0) return last;
        return node.path("ohlc").path("close").asDouble(0);
    }

    private List<String> alternateSpotKeys(String spotKey) {
        if (spotKey == null) return List.of();
        List<String> alts = new ArrayList<>();
        // Common Kite index key variants
        if (spotKey.contains("NIFTY 50")) {
            alts.add("NSE:NIFTY50");
            alts.add("NSE:Nifty 50");
        } else if (spotKey.contains("NIFTY BANK")) {
            alts.add("NSE:NIFTYBANK");
            alts.add("NSE:Nifty Bank");
        } else if (spotKey.contains("NIFTY FIN")) {
            alts.add("NSE:NIFTY FIN SERVICE");
            alts.add("NSE:Nifty Fin Service");
        } else if (spotKey.contains("MID SELECT")) {
            alts.add("NSE:NIFTY MID SELECT");
            alts.add("NSE:Nifty Mid Select");
        }
        return alts;
    }
}


