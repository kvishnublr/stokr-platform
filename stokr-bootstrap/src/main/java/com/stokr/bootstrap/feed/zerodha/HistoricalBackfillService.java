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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backfills missing 1m candles from Zerodha's historical API for all subscribed instruments.
 *
 * Runs:
 *   1. Once at startup (60s delay — waits for WebSocket to populate the instrument registry).
 *   2. Daily at 8:55 AM IST on trading days (before NSE market open at 9:15 AM).
 *
 * For each symbol it checks the last stored candle date.
 * If there are missing trading days within the configured lookback window,
 * it fetches 1m OHLCV bars from Zerodha and bulk-inserts them.
 *
 * Rate limiting: 350ms between API calls to stay within Zerodha's ~3 req/sec limit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalBackfillService {

    private static final ZoneId IST        = ZoneId.of("Asia/Kolkata");
    private static final String TIMEFRAME  = "1m";
    private static final String VENDOR     = "ZERODHA";
    private static final long   RATE_MS    = 350;

    private final InstrumentRegistryService     instrumentRegistry;
    private final ZerodhaKiteApiClient          kiteApiClient;
    private final ZerodhaBrokerProperties       zerodhaBrokerProperties;
    private final FieldCipher                   fieldCipher;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final MarketdataCandleRepository    candleRepository;

    @Value("${stokr.backfill.lookback-days:5}")
    private int lookbackDays;

    @Value("${stokr.backfill.enabled:true}")
    private boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Startup backfill — 60s delay gives WebSocket time to fetch instruments. */
    @Scheduled(initialDelayString = "60000", fixedDelayString = "86400000")
    public void backfillOnStartup() {
        runBackfill("startup");
    }

    /** Daily pre-market backfill — runs at 8:55 AM IST Mon–Fri. */
    @Scheduled(cron = "0 55 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void backfillPreMarket() {
        runBackfill("pre-market");
    }

    private void runBackfill(String trigger) {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) {
            log.info("backfill.skip reason=already_running trigger={}", trigger);
            return;
        }
        try {
            doBackfill(trigger);
        } finally {
            running.set(false);
        }
    }

    private void doBackfill(String trigger) {
        if (instrumentRegistry.isEmpty()) {
            log.info("backfill.skip reason=registry_empty trigger={} (WebSocket not connected yet)", trigger);
            return;
        }

        String accessToken = resolveAccessToken();
        if (accessToken == null) {
            log.warn("backfill.skip reason=no_access_token trigger={}", trigger);
            return;
        }
        String apiKey = zerodhaBrokerProperties.getApiKey();

        Map<String, Integer> symbolToToken = instrumentRegistry.getSymbolToToken();
        LocalDate today    = LocalDate.now(IST);
        LocalDate fromDate = today.minusDays(lookbackDays);
        List<LocalDate> tradingDays = tradingDaysBetween(fromDate, today);

        log.info("backfill.start trigger={} symbols={} lookback_days={} trading_days={}",
                trigger, symbolToToken.size(), lookbackDays, tradingDays.size());

        int filled = 0, skipped = 0, failed = 0;

        for (Map.Entry<String, Integer> entry : symbolToToken.entrySet()) {
            String symbol = entry.getKey();
            int token     = entry.getValue();

            try {
                List<LocalDate> missingDays = findMissingDays(symbol, tradingDays);
                if (missingDays.isEmpty()) {
                    skipped++;
                    continue;
                }

                // Fetch from earliest missing day to today in one call
                LocalDate fetchFrom = missingDays.get(0);
                Instant from = fetchFrom.atTime(9, 0).atZone(IST).toInstant();
                Instant to   = today.atTime(15, 45).atZone(IST).toInstant();

                List<MarketdataCandle> candles = fetchCandles(apiKey, accessToken, symbol, token, from, to);
                if (!candles.isEmpty()) {
                    saveNewCandles(symbol, candles, tradingDays);
                    filled++;
                    log.debug("backfill.filled symbol={} candles={} days={}", symbol, candles.size(), missingDays.size());
                }

                Thread.sleep(RATE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("backfill.interrupted symbol={}", symbol);
                break;
            } catch (Exception ex) {
                failed++;
                log.warn("backfill.failed symbol={} {}", symbol, ex.getMessage());
                try { Thread.sleep(RATE_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.info("backfill.done trigger={} filled={} skipped={} failed={}", trigger, filled, skipped, failed);
    }

    private List<LocalDate> findMissingDays(String symbol, List<LocalDate> tradingDays) {
        // Find the latest candle we already have
        Instant latestCandle = candleRepository
                .findTopBySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbol, TIMEFRAME)
                .map(MarketdataCandle::getOpenTime)
                .orElse(null);

        LocalDate latestDay = latestCandle == null ? null
                : latestCandle.atZone(IST).toLocalDate();

        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate day : tradingDays) {
            if (latestDay == null || day.isAfter(latestDay)) {
                missing.add(day);
            }
        }
        return missing;
    }

    private List<MarketdataCandle> fetchCandles(String apiKey, String accessToken,
                                                String symbol, int token,
                                                Instant from, Instant to) {
        JsonNode response = kiteApiClient.getHistoricalCandles(apiKey, accessToken, token, "minute", from, to);
        JsonNode candlesNode = response.path("data").path("candles");
        List<MarketdataCandle> result = new ArrayList<>();
        if (!candlesNode.isArray()) return result;

        for (JsonNode row : candlesNode) {
            // Zerodha format: [datetime, open, high, low, close, volume, oi]
            if (!row.isArray() || row.size() < 6) continue;
            try {
                Instant openTime = Instant.parse(normalizeKiteDateTime(row.get(0).asText()));
                BigDecimal open   = new BigDecimal(row.get(1).asText());
                BigDecimal high   = new BigDecimal(row.get(2).asText());
                BigDecimal low    = new BigDecimal(row.get(3).asText());
                BigDecimal close  = new BigDecimal(row.get(4).asText());
                BigDecimal volume = new BigDecimal(row.get(5).asText());

                MarketdataCandle c = new MarketdataCandle();
                c.setSymbol(symbol);
                c.setTimeframe(TIMEFRAME);
                c.setOpenTime(openTime);
                c.setOpenPrice(open);
                c.setHighPrice(high);
                c.setLowPrice(low);
                c.setClosePrice(close);
                c.setVolume(volume);
                result.add(c);
            } catch (Exception ex) {
                log.debug("backfill.parse_row_failed symbol={} row={} {}", symbol, row, ex.getMessage());
            }
        }
        return result;
    }

    @Transactional
    public void saveNewCandles(String symbol, List<MarketdataCandle> candles, List<LocalDate> expectedDays) {
        // Only insert candles for days where we have no data (skip if day already exists)
        for (LocalDate day : expectedDays) {
            Instant dayStart = day.atTime(9, 15).atZone(IST).toInstant();
            Instant dayEnd   = day.atTime(15, 30).atZone(IST).toInstant();

            long existing = candleRepository.countBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalse(
                    symbol, TIMEFRAME, dayStart, dayEnd);
            if (existing > 0) continue;

            List<MarketdataCandle> dayCandles = candles.stream()
                    .filter(c -> !c.getOpenTime().isBefore(dayStart) && !c.getOpenTime().isAfter(dayEnd))
                    .toList();
            if (!dayCandles.isEmpty()) {
                candleRepository.saveAll(dayCandles);
            }
        }
    }

    private static List<LocalDate> tradingDaysBetween(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = from;
        while (!d.isAfter(to)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.plusDays(1);
        }
        return days;
    }

    /** Kite returns "2024-01-15T09:15:00+0530" — convert to ISO-8601 UTC for Instant.parse. */
    private static String normalizeKiteDateTime(String kiteTs) {
        if (kiteTs == null) return kiteTs;
        // Replace "+0530" with "+05:30" so Instant.parse (OffsetDateTime) works
        return kiteTs.replaceAll("\\+0530$", "+05:30");
    }

    private String resolveAccessToken() {
        try {
            PlatformBrokerFeedSession session = sessionRepository
                    .findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).orElse(null);
            if (session == null || session.getAccessTokenEnc() == null) return null;
            if (session.getTokenExpiresAt() != null && session.getTokenExpiresAt().isBefore(Instant.now())) return null;
            String token = fieldCipher.decrypt(session.getAccessTokenEnc());
            return (token == null || token.isBlank()) ? null : token;
        } catch (Exception ex) {
            log.warn("backfill.token_resolve_failed {}", ex.getMessage());
            return null;
        }
    }
}
