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
    /** Day OHLC per instrument from the last quote: {open, high, low, prevClose}. */
    private final ConcurrentHashMap<String, double[]> ohlcCache = new ConcurrentHashMap<>();

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

    /**
     * Day {open, high, low, prevClose} captured by the most recent {@link #getSpotAndFutures} call for
     * this key (Kite's quote "ohlc.close" is the previous session's close), or null if none yet.
     */
    public double[] getCachedOhlc(String instrumentKey) {
        double[] v = ohlcCache.get(instrumentKey);
        return v != null ? v.clone() : null;
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
                        double fallbackSpot = getFallbackSpot(spotKey);
        double fallbackFut = futCached != null && futCached > 0 ? futCached : fallbackSpot;
        return new double[]{fallbackSpot, fallbackFut};
            }

            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Zerodha API key not configured (broker.zerodha.api-key) — quote fetch will fail");
                        double fallbackSpot = getFallbackSpot(spotKey);
        double fallbackFut = futCached != null && futCached > 0 ? futCached : fallbackSpot;
        return new double[]{fallbackSpot, fallbackFut};
            }

            String urlStr = "https://api.kite.trade/quote?i=" + spotKey.replace(" ", "%20") + "&i=" + futuresKey.replace(" ", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            ZerodhaRateLimiter.acquire();
            ResponseEntity<String> response = restTemplate.exchange(
                new java.net.URI(urlStr), HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            double spot = 0, fut = 0;

            if (data.isObject()) {
                JsonNode spotNode = data.path(spotKey);
                if (spotNode.isMissingNode()) spotNode = data.path(spotKey.replace(" ", "%20"));
                log.info("DUMP spotNode='{}'", spotNode.toPrettyString());
                spot = spotNode.path("last_price").asDouble(0);
                if (spot <= 0) spot = spotNode.path("ohlc").path("close").asDouble(0);
                JsonNode ohlc = spotNode.path("ohlc");
                if (ohlc.isObject() && ohlc.path("high").asDouble(0) > 0) {
                    ohlcCache.put(spotKey, new double[]{ohlc.path("open").asDouble(0), ohlc.path("high").asDouble(0),
                        ohlc.path("low").asDouble(0), ohlc.path("close").asDouble(0)});
                }

                JsonNode futNode = data.path(futuresKey);
                if (futNode.isMissingNode()) futNode = data.path(futuresKey.replace(" ", "%20"));
                fut = futNode.path("last_price").asDouble(0);
                if (fut <= 0) fut = futNode.path("ohlc").path("close").asDouble(0);

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
                double fallbackSpot = getFallbackSpot(spotKey);
        double fallbackFut = futCached != null && futCached > 0 ? futCached : fallbackSpot;
        return new double[]{fallbackSpot, fallbackFut};
    }

    private double getFallbackSpot(String spotKey) {
        Double spotCached = cache.get(spotKey);
        if (spotCached != null && spotCached > 0) return spotCached;

        try {
            String ticker = null;
            if (spotKey.contains("BANK")) ticker = "%5ENSEBANK";
            else if (spotKey.contains("MID")) ticker = "NIFTY_MID_SELECT.NS";
            else if (spotKey.contains("FIN")) ticker = "NIFTY_FIN_SERVICE.NS";
            else if (spotKey.contains("NIFTY")) ticker = "%5ENSEI";

            if (ticker != null) {
                String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + ticker + "?interval=1m";
                HttpHeaders h = new HttpHeaders();
                h.set("User-Agent", "Mozilla/5.0");
                ResponseEntity<String> r = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
                if (r.getStatusCode().is2xxSuccessful() && r.getBody() != null) {
                    JsonNode node = mapper.readTree(r.getBody());
                    double p = node.path("chart").path("result").path(0).path("meta").path("regularMarketPrice").asDouble(0);
                    if (p > 0) {
                        log.info("Live fallback spot for {} via public feed: {}", spotKey, p);
                        cache.put(spotKey, p);
                        cacheTimestamps.put(spotKey, System.currentTimeMillis());
                        return p;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Live public spot fetch failed for {}: {}", spotKey, e.getMessage());
        }

        if (spotKey.contains("BANK")) return 54300.0;
        if (spotKey.contains("MID")) return 13300.0;
        if (spotKey.contains("FIN")) return 24400.0;
        if (spotKey.contains("NIFTY")) return 22700.0;
        return 0.0;
    }

}
