package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.user.broker.ZerodhaKiteApiClient;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background service to load 5-year historical data for backtesting
 * One-time process that fills whatever date range is possible
 * Reports progress as it completes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestHistoricalDataLoader {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String TIMEFRAME = "1m";
    private static final long RATE_MS = 350;
    private static final int CHUNK_DAYS = 55; // Zerodha API max for minute data

    private final InstrumentRegistryService instrumentRegistry;
    private final ZerodhaKiteApiClient kiteApiClient;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final FieldCipher fieldCipher;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final MarketdataCandleRepository candleRepository;

    @Value("${stokr.backtest.historical-lookback-days:1825}")  // 5 years
    private int historicalLookbackDays;

    @Value("${stokr.backfill.enabled:true}")
    private boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalCandlesLoaded = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> symbolProgress = new ConcurrentHashMap<>();
    private final List<String> failedSymbols = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong startTime = new AtomicLong(0);

    /**
     * Start background historical data load (5 years)
     * Non-blocking - returns immediately
     */
    public void startBackgroundHistoricalLoad(List<String> targetSymbols) {
        if (!enabled) {
            log.warn("loader.skip reason=disabled");
            return;
        }

        new Thread(() -> loadHistoricalDataBackground(targetSymbols), "BacktestHistoricalLoader").start();
    }

    /**
     * Load historical data for specific symbols in background
     */
    private void loadHistoricalDataBackground(List<String> targetSymbols) {
        if (!running.compareAndSet(false, true)) {
            log.warn("loader.skip reason=already_running");
            return;
        }

        startTime.set(System.currentTimeMillis());
        log.info("loader.start lookback_days={}", historicalLookbackDays);

        try {
            doLoadHistoricalData(targetSymbols);
        } finally {
            running.set(false);
            reportProgress();
        }
    }

    /**
     * Core historical loading logic
     */
    @Transactional
    private void doLoadHistoricalData(List<String> targetSymbols) {
        if (instrumentRegistry.isEmpty()) {
            log.warn("loader.skip reason=registry_empty");
            return;
        }

        String accessToken = resolveAccessToken();
        if (accessToken == null) {
            log.warn("loader.skip reason=no_access_token");
            return;
        }

        String apiKey = zerodhaBrokerProperties.getApiKey();
        Map<String, Integer> symbolToToken = instrumentRegistry.getSymbolToToken();

        // Filter to target symbols if provided
        Map<String, Integer> toLoad = targetSymbols.isEmpty() ? symbolToToken :
                symbolToToken.entrySet().stream()
                        .filter(e -> targetSymbols.contains(e.getKey()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        LocalDate today = LocalDate.now(IST);
        LocalDate fromDate = today.minusDays(historicalLookbackDays);

        log.info("loader.config symbols={} date_range={} to {}",
                toLoad.size(), fromDate, today);

        int successCount = 0;
        int skippedCount = 0;

        for (Map.Entry<String, Integer> entry : toLoad.entrySet()) {
            String symbol = entry.getKey();
            int token = entry.getValue();

            try {
                long candlesBeforeLoad = candleRepository
                        .countBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalse(
                                symbol, TIMEFRAME,
                                fromDate.atStartOfDay(IST).toInstant(),
                                today.atTime(23, 59).atZone(IST).toInstant()
                        );

                if (candlesBeforeLoad > 0) {
                    skippedCount++;
                    symbolProgress.put(symbol, candlesBeforeLoad);
                    log.debug("loader.skip symbol={} existing_candles={}", symbol, candlesBeforeLoad);
                    continue;
                }

                // Load in 55-day chunks to respect Zerodha API limits
                long symbolCandles = 0;
                LocalDate chunkStart = fromDate;

                while (chunkStart.isBefore(today)) {
                    LocalDate chunkEnd = chunkStart.plus(CHUNK_DAYS, ChronoUnit.DAYS);
                    if (chunkEnd.isAfter(today)) {
                        chunkEnd = today;
                    }

                    Instant from = chunkStart.atTime(9, 0).atZone(IST).toInstant();
                    Instant to = chunkEnd.atTime(15, 45).atZone(IST).toInstant();

                    log.debug("loader.chunk symbol={} period={} to {}", symbol, chunkStart, chunkEnd);

                    List<MarketdataCandle> candles = fetchCandles(apiKey, accessToken, symbol, token, from, to);
                    if (!candles.isEmpty()) {
                        saveCandles(symbol, candles);
                        symbolCandles += candles.size();
                        totalCandlesLoaded.addAndGet(candles.size());
                    }

                    chunkStart = chunkEnd.plus(1, ChronoUnit.DAYS);
                    Thread.sleep(RATE_MS);
                }

                if (symbolCandles > 0) {
                    successCount++;
                    symbolProgress.put(symbol, symbolCandles);
                    log.info("loader.success symbol={} candles_loaded={}", symbol, symbolCandles);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("loader.interrupted symbol={}", symbol);
                failedSymbols.add(symbol);
                break;
            } catch (Exception ex) {
                log.warn("loader.failed symbol={} error={}", symbol, ex.getMessage());
                failedSymbols.add(symbol);
                try {
                    Thread.sleep(RATE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("loader.completed success={} skipped={} failed={} total_candles={}",
                successCount, skippedCount, failedSymbols.size(), totalCandlesLoaded.get());
    }

    /**
     * Fetch candles from Zerodha API
     */
    private List<MarketdataCandle> fetchCandles(
            String apiKey, String accessToken, String symbol, int token,
            Instant from, Instant to) throws Exception {

        List<MarketdataCandle> result = new ArrayList<>();
        JsonNode response = kiteApiClient.getHistoricalCandles(
                apiKey,
                accessToken,
                token,
                "minute",
                from,
                to
        );
        JsonNode candles = response == null ? null : response.path("data").path("candles");

        if (candles != null && candles.isArray()) {
            for (JsonNode bar : candles) {
                try {
                    if (!bar.isArray() || bar.size() < 6) {
                        continue;
                    }
                    MarketdataCandle candle = new MarketdataCandle();
                    candle.setSymbol(symbol);
                    candle.setTimeframe(TIMEFRAME);
                    candle.setOpenTime(Instant.parse(bar.get(0).asText()));
                    candle.setOpenPrice(new BigDecimal(bar.get(1).asText()));
                    candle.setHighPrice(new BigDecimal(bar.get(2).asText()));
                    candle.setLowPrice(new BigDecimal(bar.get(3).asText()));
                    candle.setClosePrice(new BigDecimal(bar.get(4).asText()));
                    candle.setVolume(new BigDecimal(bar.get(5).asText()));
                    result.add(candle);
                } catch (Exception e) {
                    log.debug("loader.parse_error symbol={} {}", symbol, e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * Save candles to database
     */
    @Transactional
    private void saveCandles(String symbol, List<MarketdataCandle> candles) {
        for (MarketdataCandle candle : candles) {
            candleRepository.save(candle);
        }
    }

    /**
     * Resolve Zerodha access token
     */
    private String resolveAccessToken() {
        try {
            Optional<PlatformBrokerFeedSession> session = sessionRepository
                    .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc("ZERODHA");

            if (session.isPresent()) {
                String enc = session.get().getAccessTokenEnc();
                if (enc != null && !enc.isBlank()) {
                    return fieldCipher.decrypt(enc);
                }
            }
        } catch (Exception e) {
            log.warn("loader.token_error {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get current progress
     */
    public Map<String, Object> getProgress() {
        long elapsedMs = System.currentTimeMillis() - startTime.get();
        return Map.of(
                "running", running.get(),
                "total_candles_loaded", totalCandlesLoaded.get(),
                "symbols_completed", symbolProgress.size(),
                "failed_symbols", failedSymbols.size(),
                "elapsed_seconds", elapsedMs / 1000,
                "symbol_progress", new HashMap<>(symbolProgress),
                "failed_list", new ArrayList<>(failedSymbols)
        );
    }

    /**
     * Report final progress
     */
    private void reportProgress() {
        long elapsedMs = System.currentTimeMillis() - startTime.get();
        log.info("""
                ╔════════════════════════════════════════════════════════╗
                ║     HISTORICAL DATA LOAD COMPLETE                      ║
                ╠════════════════════════════════════════════════════════╣
                ║  Total Candles Loaded: {:>36} ║
                ║  Symbols Completed:    {:>36} ║
                ║  Failed Symbols:       {:>36} ║
                ║  Elapsed Time:         {:<36} ║
                ╚════════════════════════════════════════════════════════╝
                """,
                totalCandlesLoaded.get(),
                symbolProgress.size(),
                failedSymbols.size(),
                (elapsedMs / 1000) + "s"
        );

        if (!symbolProgress.isEmpty()) {
            log.info("Loaded symbols:");
            symbolProgress.forEach((sym, count) ->
                    log.info("  ✓ {} → {} candles", sym, count)
            );
        }

        if (!failedSymbols.isEmpty()) {
            log.warn("Failed symbols:");
            failedSymbols.forEach(sym -> log.warn("  ✗ {}", sym));
        }
    }

    /**
     * Check if loading is in progress
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get total loaded
     */
    public long getTotalLoaded() {
        return totalCandlesLoaded.get();
    }
}
