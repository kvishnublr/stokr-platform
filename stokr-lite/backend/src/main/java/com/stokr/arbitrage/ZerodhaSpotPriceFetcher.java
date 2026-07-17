package com.stokr.arbitrage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ZerodhaSpotPriceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaSpotPriceFetcher.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private final ZerodhaTokenManager tokenManager;

    @Value("${zerodha.api-key:zazlrld244cc6jf0}")
    private String apiKey;

    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3000;

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

        try {
            String token = getAuthToken();
            if (token == null) {
                log.error("No auth token for spot price fetch");
                return 0;
            }

            String encodedKey = instrumentKey.replace(" ", "%20");
            String urlStr = "https://api.kite.trade/quote?i=" + encodedKey;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            if (data.isObject() && data.size() > 0) {
                var fields = data.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    JsonNode quoteNode = entry.getValue();
                    if (quoteNode.isObject()) {
                        String instrumentName = entry.getKey();
                        double lastPrice = quoteNode.path("last_price").asDouble(0);
                        double ohlcClose = quoteNode.path("ohlc").path("close").asDouble(0);
                        double ohlcOpen = quoteNode.path("ohlc").path("open").asDouble(0);
                        double ohlcHigh = quoteNode.path("ohlc").path("high").asDouble(0);
                        double ohlcLow = quoteNode.path("ohlc").path("low").asDouble(0);

                        double price = lastPrice;
                        if (price <= 0 && ohlcClose > 0) {
                            price = ohlcClose;
                            log.warn("{} last_price=0, falling back to ohlc.close={}", instrumentKey, price);
                        }
                        // Validate: if last_price is outside OHLC range, use ohlc.close
                        if (price > 0 && ohlcHigh > 0 && ohlcLow > 0) {
                            if (price < ohlcLow * 0.99 || price > ohlcHigh * 1.01) {
                                log.warn("{} last_price={} outside OHLC range [{}, {}], using ohlc.close={}",
                                    instrumentKey, price, ohlcLow, ohlcHigh, ohlcClose);
                                price = ohlcClose > 0 ? ohlcClose : price;
                            }
                        }

                        if (price > 0) {
                            boolean isFutures = instrumentKey.contains("FUT");
                            if (isFutures) {
                                log.info("FUTURES QUOTE {}: last_price={}, ohlc=(O={},H={},L={},C={})",
                                    instrumentKey, lastPrice, ohlcOpen, ohlcHigh, ohlcLow, ohlcClose);
                            }
                            cache.put(instrumentKey, price);
                            cacheTimestamps.put(instrumentKey, now);
                            return price;
                        }
                    }
                }
            }

            log.warn("No price found for {}", instrumentKey);
        } catch (Exception e) {
            log.error("Failed to get spot price for {}: {}", instrumentKey, e.getMessage());
        }
        return 0;
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

    /**
     * Fetch spot and futures prices in a SINGLE API call to avoid rate limiting.
     * Returns [spot, futures].
     */
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
                return new double[]{0, 0};
            }

            String encodedSpot = spotKey.replace(" ", "%20");
            String encodedFut = futuresKey.replace(" ", "%20");
            String urlStr = "https://api.kite.trade/quote?i=" + encodedSpot + "&i=" + encodedFut;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            double spot = 0, fut = 0;

            if (data.isObject()) {
                var fields = data.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    JsonNode quoteNode = entry.getValue();
                    if (!quoteNode.isObject()) continue;

                    String instrumentName = entry.getKey();
                    double lastPrice = quoteNode.path("last_price").asDouble(0);
                    double ohlcClose = quoteNode.path("ohlc").path("close").asDouble(0);
                    double ohlcOpen = quoteNode.path("ohlc").path("open").asDouble(0);
                    double ohlcHigh = quoteNode.path("ohlc").path("high").asDouble(0);
                    double ohlcLow = quoteNode.path("ohlc").path("low").asDouble(0);

                    double price = lastPrice;
                    if (price <= 0 && ohlcClose > 0) {
                        price = ohlcClose;
                    }
                    if (price > 0 && ohlcHigh > 0 && ohlcLow > 0) {
                        if (price < ohlcLow * 0.99 || price > ohlcHigh * 1.01) {
                            log.warn("{} last_price={} outside OHLC [{}, {}], using ohlc.close={}",
                                instrumentName, price, ohlcLow, ohlcHigh, ohlcClose);
                            price = ohlcClose > 0 ? ohlcClose : price;
                        }
                    }

                    if (instrumentName.contains(spotKey.replace("NFO:", "").replace("NSE:", "")) || instrumentName.equals(spotKey)) {
                        spot = price;
                        cache.put(spotKey, price);
                        cacheTimestamps.put(spotKey, now);
                    }
                    if (instrumentName.contains(futuresKey.replace("NFO:", "").replace("NSE:", "")) || instrumentName.equals(futuresKey)) {
                        fut = price;
                        cache.put(futuresKey, price);
                        cacheTimestamps.put(futuresKey, now);
                        log.info("BATCH FUTURES {}: last_price={}, ohlc=(O={},H={},L={},C={}), validated={}",
                            instrumentName, lastPrice, ohlcOpen, ohlcHigh, ohlcLow, ohlcClose, price);
                    }
                }
            }

            return new double[]{spot, fut};
        } catch (Exception e) {
            log.error("Failed batch fetch: {}", e.getMessage());
        }
        return new double[]{0, 0};
    }
}
