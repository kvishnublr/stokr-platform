package com.stokr.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.engine.CandleData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Backfills historical daily candles from Zerodha Kite Historical API.
 * <p>
 * Rate limit: Kite allows 3600 requests/hour. At 200ms delay, we do ~300/min
 * which is well within limits (~18000/hour). Still, we add a safety delay.
 * <p>
 * For ~500 symbols × 6 months of daily data, this fetches ~500 requests
 * (one per symbol, Kite returns all candles for the date range in one call).
 * <p>
 * Usage: POST /api/admin/backfill/historical?months=6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalDataBackfillService {

    private static final String KITE_API_BASE = "https://api.kite.trade";
    private static final String INSTRUMENTS_URL = "https://api.kite.trade/instruments";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int DELAY_MS = 250; // safety delay between API calls
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    private final BrokerAccountRepository brokerAccountRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private final RestTemplate restTemplate = buildRestTemplate();

    // Progress tracking
    private final AtomicInteger completed = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final AtomicInteger totalCandles = new AtomicInteger(0);
    private volatile String status = "idle";
    private volatile String currentSymbol = null;
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public Map<String, Object> getProgress() {
        return Map.of(
            "status", status,
            "completed", completed.get(),
            "failed", failed.get(),
            "totalSymbols", ZerodhaLiveDataScheduler.NIFTY_500.size(),
            "totalCandles", totalCandles.get(),
            "currentSymbol", currentSymbol != null ? currentSymbol : "",
            "errors", new HashMap<>(errors)
        );
    }

    public void backfill(int months) {
        if ("running".equals(status)) {
            throw new IllegalStateException("Backfill already in progress. Check /api/admin/backfill/status");
        }

        completed.set(0);
        failed.set(0);
        totalCandles.set(0);
        errors.clear();
        status = "running";

        Thread.ofVirtual().start(() -> {
            try {
                doBackfill(months);
                status = "completed";
            } catch (Exception e) {
                log.error("Backfill failed", e);
                status = "error: " + e.getMessage();
            }
        });
    }

    private void doBackfill(int months) throws Exception {
        String accessToken = resolveToken();
        if (accessToken == null) {
            status = "error: No active Zerodha access token found";
            return;
        }

        log.info("=== Starting historical backfill: {} months ===", months);
        log.info("Fetching instrument master from Kite...");

        // Step 1: Fetch instrument master to map NSE symbol → instrument_token
        Map<String, Long> symbolToToken = fetchInstrumentTokens();
        log.info("Loaded {} instrument tokens", symbolToToken.size());

        // Step 2: Date range
        LocalDate endDate = LocalDate.now(IST).minusDays(1);
        LocalDate startDate = endDate.minusMonths(months);
        log.info("Date range: {} to {}", startDate.format(DATE_FMT), endDate.format(DATE_FMT));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + zerodhaApiKey + ":" + accessToken);
        headers.set("X-Kite-Version", "3");

        int total = ZerodhaLiveDataScheduler.NIFTY_500.size();
        List<List<String>> batches = partition(new ArrayList<>(ZerodhaLiveDataScheduler.NIFTY_500), 50);

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<String> batch = batches.get(batchIdx);
            log.info("=== Batch {}/{} ({} symbols) ===", batchIdx + 1, batches.size(), batch.size());

            for (String symbol : batch) {
                currentSymbol = symbol;
                Long token = symbolToToken.get(symbol);

                if (token == null) {
                    log.warn("No instrument token for {}, skipping", symbol);
                    errors.put(symbol, "No instrument token");
                    failed.incrementAndGet();
                    continue;
                }

                try {
                    String url = KITE_API_BASE + "/instruments/historical/" + token + "/day"
                        + "?from=" + startDate.format(DATE_FMT)
                        + "&to=" + endDate.format(DATE_FMT);

                    ResponseEntity<String> resp = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

                    JsonNode root = mapper.readTree(resp.getBody());
                    if (!"success".equals(root.path("status").asText())) {
                        String msg = root.path("message").asText("Unknown error");
                        log.warn("Kite historical API failed for {}: {}", symbol, msg);
                        errors.put(symbol, msg);
                        failed.incrementAndGet();
                        continue;
                    }

                    JsonNode candles = root.path("data").path("candles");
                    if (!candles.isArray() || candles.size() == 0) {
                        log.debug("No candles for {} in date range", symbol);
                        completed.incrementAndGet();
                        continue;
                    }

                    List<Object[]> batchArgs = new ArrayList<>();
                    for (JsonNode candle : candles) {
                        // Kite format: [timestamp, open, high, low, close, volume]
                        if (!candle.isArray() || candle.size() < 6) continue;

                        String ts = candle.get(0).asText();  // "2025-01-03T09:15:00+0530"
                        LocalDateTime candleTime = parseKiteTimestamp(ts);
                        BigDecimal open = new BigDecimal(candle.get(1).asText());
                        BigDecimal high = new BigDecimal(candle.get(2).asText());
                        BigDecimal low = new BigDecimal(candle.get(3).asText());
                        BigDecimal close = new BigDecimal(candle.get(4).asText());
                        long volume = candle.get(5).asLong();

                        batchArgs.add(new Object[]{
                            symbol, "1d", candleTime,
                            open, high, low, close, volume
                        });
                    }

                    if (!batchArgs.isEmpty()) {
                        upsertDailyCandles(batchArgs);
                        totalCandles.addAndGet(batchArgs.size());
                    }

                    completed.incrementAndGet();

                    if (completed.get() % 50 == 0) {
                        log.info("Progress: {}/{} symbols done, {} candles stored",
                            completed.get(), total, totalCandles.get());
                    }

                } catch (Exception e) {
                    log.error("Failed to backfill {}: {}", symbol, e.getMessage());
                    errors.put(symbol, e.getMessage());
                    failed.incrementAndGet();
                }

                // Rate-limit safety delay
                try { Thread.sleep(DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }

        log.info("=== Backfill complete: {} symbols OK, {} failed, {} candles stored ===",
            completed.get(), failed.get(), totalCandles.get());
        currentSymbol = null;
    }

    /**
     * Downloads the Kite instrument master CSV (gzipped), parses it,
     * and returns a map of NSE trading symbol → instrument_token.
     */
    private Map<String, Long> fetchInstrumentTokens() throws Exception {
        Map<String, Long> map = new HashMap<>();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
        headers.set("Accept-Encoding", "gzip");

        ResponseEntity<byte[]> resp = restTemplate.exchange(
            INSTRUMENTS_URL, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        if (resp.getBody() == null) {
            throw new RuntimeException("Empty instruments response from Kite");
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(
                    new java.io.ByteArrayInputStream(resp.getBody()))))) {

            // Skip header line
            String header = reader.readLine();
            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length < 8) continue;

                // Cols: instrument_token, exchange_token, tradingsymbol, name,
                //       last_price, expiry, strike, tick_size, lot_size,
                //       instrument_type, segment, exchange
                String exchange = cols[11];
                String segment = cols[10];
                String symbol = cols[2];
                long token;

                try {
                    token = Long.parseLong(cols[0]);
                } catch (NumberFormatException e) {
                    continue;
                }

                // We only want NSE equity
                if ("NSE".equalsIgnoreCase(exchange) && "EQ".equalsIgnoreCase(segment)) {
                    map.put(symbol, token);
                    count++;
                }
            }

            log.info("Parsed {} NSE equity instruments from Kite master", count);
        }

        return map;
    }

    /**
     * Parses Kite timestamp format: "2025-01-03T09:15:00+0530"
     */
    private LocalDateTime parseKiteTimestamp(String ts) {
        // Kite returns timestamps with timezone offset like +0530
        // We need to convert to IST LocalDateTime for storage
        try {
            java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(
                ts.replace("+0530", "+0530"), // normalize
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return zdt.withZoneSameInstant(IST).toLocalDateTime();
        } catch (Exception e) {
            // Fallback: try parsing as ISO
            try {
                return LocalDateTime.parse(ts.substring(0, 19));
            } catch (Exception ex) {
                log.warn("Could not parse timestamp: {}", ts);
                return LocalDateTime.now(IST);
            }
        }
    }

    /**
     * Batch upsert daily candles via JDBC with PostgreSQL ON CONFLICT.
     */
    private void upsertDailyCandles(List<Object[]> batchArgs) {
        String sql =
            "INSERT INTO candle_data (symbol, timeframe, \"timestamp\", \"open\", high, low, \"close\", volume, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (symbol, timeframe, \"timestamp\") DO UPDATE SET " +
            "  \"open\"  = EXCLUDED.\"open\", " +
            "  high    = EXCLUDED.high, " +
            "  low     = EXCLUDED.low, " +
            "  \"close\" = EXCLUDED.\"close\", " +
            "  volume  = EXCLUDED.volume";

        jdbc.batchUpdate(sql, batchArgs);
    }

    private String resolveToken() {
        if (zerodhaApiKey == null || zerodhaApiKey.isBlank()) return null;
        return brokerAccountRepo.findByBrokerNameAndStatus("ZERODHA", "ACTIVE")
            .stream()
            .filter(a -> a.getAccessToken() != null && !a.isTokenExpired())
            .findFirst()
            .map(BrokerAccount::getAccessToken)
            .orElse(null);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        return parts;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(15_000);
        f.setReadTimeout(30_000);
        return new RestTemplate(f);
    }
}
