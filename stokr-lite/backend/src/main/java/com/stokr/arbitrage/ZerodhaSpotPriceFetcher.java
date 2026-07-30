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

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ZerodhaSpotPriceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaSpotPriceFetcher.class);

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${broker.zerodha.api-key:}")
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
                JsonNode spotNode = data.path(spotKey);
                if (spotNode.isMissingNode()) spotNode = data.path(spotKey.replace(" ", "%20"));
                spot = spotNode.path("last_price").asDouble(0);
                if (spot <= 0) spot = spotNode.path("ohlc").path("close").asDouble(0);

                JsonNode futNode = data.path(futuresKey);
                if (futNode.isMissingNode()) futNode = data.path(futuresKey.replace(" ", "%20"));
                fut = futNode.path("last_price").asDouble(0);
                if (fut <= 0) fut = futNode.path("ohlc").path("close").asDouble(spot);

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
            return new double[]{spot, fut};

        } catch (Exception e) {
            log.error("Failed batch fetch for {} / {}: {}", spotKey, futuresKey, e.getMessage());
        }
        return new double[]{0, 0};
    }
}


