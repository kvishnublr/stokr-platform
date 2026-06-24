package com.stokr.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches live quotes from Zerodha every minute and stores 1-min candles to DB.
 * Also maintains in-memory LTP and ORB caches used by exits and strategy evaluation.
 *
 * Called synchronously by ExecutionEngine.runScanCycle() so data is always fresh
 * before the strategy scan runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaLiveDataScheduler {

    private final BrokerAccountRepository brokerAccountRepository;
    private final CandleDataRepository candleDataRepository;

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    private static final String KITE_API_BASE = "https://api.kite.trade";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // LTP cache — updated every minute
    private final Map<String, BigDecimal> ltpCache       = new ConcurrentHashMap<>();
    // ORB levels — snapshotted at 9:30 IST each day
    private final Map<String, BigDecimal> orbHighCache   = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> orbLowCache    = new ConcurrentHashMap<>();
    // Previous values needed for per-minute candle construction
    private final Map<String, BigDecimal> prevLtpCache   = new ConcurrentHashMap<>();
    private final Map<String, Long>       prevVolCache   = new ConcurrentHashMap<>();

    // NIFTY 50 — primary universe; NIFTY 100 additions loaded separately
    public static final List<String> NIFTY_50 = List.of(
        "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK",
        "SBIN", "BHARTIARTL", "ITC", "KOTAKBANK", "LT",
        "HINDUNILVR", "AXISBANK", "MARUTI", "BAJFINANCE", "ASIANPAINT",
        "SUNPHARMA", "TITAN", "ULTRACEMCO", "WIPRO", "HCLTECH",
        "TATAMOTORS", "ONGC", "NTPC", "POWERGRID", "ADANIPORTS",
        "JSWSTEEL", "TATASTEEL", "COALINDIA", "M&M", "TECHM",
        "BAJAJFINSV", "BAJAJ-AUTO", "DRREDDY", "CIPLA", "DIVISLAB",
        "EICHERMOT", "GRASIM", "HDFCLIFE", "HEROMOTOCO", "HINDALCO",
        "INDUSINDBK", "NESTLEIND", "SBILIFE", "TATACONSUM", "APOLLOHOSP",
        "BPCL", "BRITANNIA", "LTIM", "UPL", "VEDL"
    );

    /**
     * Called by ExecutionEngine before every scan cycle.
     * Fetches Zerodha /quote for all NIFTY 50 in ONE API call and stores 1-min candles.
     */
    public void fetchAndStoreQuotes() {
        String accessToken = resolveToken();
        if (accessToken == null) {
            log.warn("Zerodha token unavailable — skipping live data fetch");
            return;
        }

        // Build query string: i=NSE:SYM1&i=NSE:SYM2...
        StringBuilder url = new StringBuilder(KITE_API_BASE + "/quote?");
        for (int i = 0; i < NIFTY_50.size(); i++) {
            if (i > 0) url.append("&");
            url.append("i=NSE:").append(NIFTY_50.get(i));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + zerodhaApiKey + ":" + accessToken);
        headers.set("X-Kite-Version", "3");

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                url.toString(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(resp.getBody());
            if (!"success".equals(root.path("status").asText())) {
                log.warn("Zerodha quote API returned non-success: {}", resp.getBody());
                return;
            }

            JsonNode data = root.path("data");
            LocalDateTime istNow = LocalDateTime.now(IST).withSecond(0).withNano(0);
            boolean isOrbSnapshotTime = istNow.getHour() == 9 && istNow.getMinute() == 30;
            int processedCount = 0;

            for (String symbol : NIFTY_50) {
                JsonNode q = data.path("NSE:" + symbol);
                if (q.isMissingNode()) continue;

                BigDecimal ltp      = bd(q, "last_price");
                BigDecimal avgPrice = bd(q, "average_price"); // intraday VWAP
                long totalVol       = q.path("volume").asLong(0);

                JsonNode ohlc = q.path("ohlc");
                BigDecimal dayOpen  = bd(ohlc, "open");
                BigDecimal dayHigh  = bd(ohlc, "high");
                BigDecimal dayLow   = bd(ohlc, "low");

                // Update LTP cache
                ltpCache.put(symbol, ltp);

                // Compute per-minute volume delta
                long prevVol    = prevVolCache.getOrDefault(symbol, 0L);
                long minuteVol  = Math.max(0, totalVol - prevVol);
                prevVolCache.put(symbol, totalVol);

                // Build approximate 1-min candle
                BigDecimal prevClose  = prevLtpCache.getOrDefault(symbol, dayOpen);
                BigDecimal candleOpen = prevClose;
                BigDecimal candleHigh = ltp.max(prevClose);
                BigDecimal candleLow  = ltp.min(prevClose);
                prevLtpCache.put(symbol, ltp);

                // Snapshot ORB at 9:30 (day high/low after 15 opening minutes = ORB)
                if (isOrbSnapshotTime) {
                    orbHighCache.put(symbol, dayHigh);
                    orbLowCache.put(symbol, dayLow);
                }

                // Upsert into candle_data
                Instant ts = istNow.atZone(IST).toInstant();
                CandleData candle = candleDataRepository
                    .findBySymbolAndTimeframeAndTimestamp(symbol, "1min", ts)
                    .orElseGet(CandleData::new);
                candle.setSymbol(symbol);
                candle.setTimeframe("1min");
                candle.setTimestamp(ts);
                candle.setOpen(candleOpen);
                candle.setHigh(candleHigh);
                candle.setLow(candleLow);
                candle.setClose(ltp);
                candle.setVolume(minuteVol);
                candleDataRepository.save(candle);
                processedCount++;
            }

            log.info("Zerodha live data: stored {} 1-min candles at {}", processedCount, istNow);

        } catch (Exception e) {
            log.error("Zerodha quote fetch failed: {}", e.getMessage());
        }
    }

    // ── Cache accessors used by BrokerMarketDataService + SignalProcessor ──

    public BigDecimal getLtp(String symbol) {
        return ltpCache.getOrDefault(symbol.toUpperCase(), BigDecimal.ZERO);
    }

    public BigDecimal getOrbHigh(String symbol) {
        return orbHighCache.get(symbol.toUpperCase());
    }

    public BigDecimal getOrbLow(String symbol) {
        return orbLowCache.get(symbol.toUpperCase());
    }

    /** True when a non-expired Zerodha token is available. */
    public boolean isHealthy() {
        return resolveToken() != null;
    }

    public String getHealthStatus() {
        List<BrokerAccount> accounts = brokerAccountRepository
            .findByBrokerNameAndStatus("ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) return "NO_ACCOUNT";
        boolean anyValid = accounts.stream().anyMatch(a -> !a.isTokenExpired());
        return anyValid ? "OK" : "TOKEN_EXPIRED";
    }

    // ── Helpers ──

    private String resolveToken() {
        if (zerodhaApiKey == null || zerodhaApiKey.isBlank()) return null;
        return brokerAccountRepository
            .findByBrokerNameAndStatus("ZERODHA", "ACTIVE")
            .stream()
            .filter(a -> !a.isTokenExpired() && a.getAccessToken() != null)
            .findFirst()
            .map(BrokerAccount::getAccessToken)
            .orElse(null);
    }

    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        return new BigDecimal(n.asText());
    }
}
