package com.stokr.marketdata.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Maintains ingest-side candle integrity: evicts stale partial builder state and detects obvious OHLC gaps.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleFinalizationService {

    private final CandleAggregator candleAggregator;
    private final MarketdataCandleRepository candleRepository;
    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private String marketZoneId;
    @Value("${stokr.market.session.open:09:15}")
    private String marketOpenRaw;
    @Value("${stokr.market.session.close:15:30}")
    private String marketCloseRaw;

    /**
     * Drops in-memory partial buckets older than cutoff (does not delete persisted finalized candles).
     */
    public void evictPartialStateOlderThan(Instant cutoffUtc) {
        candleAggregator.evictOlderThan(cutoffUtc);
    }

    /**
     * Finds sequences where consecutive 1m candles for a symbol have a gap larger than one period (replay diagnostics).
     */
    @Transactional(readOnly = true)
    public List<CandleGap> detectGaps(String symbol, String timeframe, Instant rangeStart, Instant rangeEnd) {
        List<MarketdataCandle> asc =
                candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        symbol, timeframe, rangeStart, rangeEnd);
        if (asc.size() < 2) {
            return List.of();
        }
        long periodMs = timeframePeriodMillis(timeframe);
        List<CandleGap> gaps = new ArrayList<>();
        for (int i = 1; i < asc.size(); i++) {
            long delta = asc.get(i).getOpenTime().toEpochMilli() - asc.get(i - 1).getOpenTime().toEpochMilli();
            if (delta > periodMs + 1) {
                gaps.add(new CandleGap(asc.get(i - 1).getOpenTime(), asc.get(i).getOpenTime(), delta - periodMs));
            }
        }
        return gaps;
    }

    /**
     * Market-hours aware continuity detection for NSE-like sessions.
     * Skips weekends and out-of-session durations so operational gaps are actionable.
     */
    @Transactional(readOnly = true)
    public List<CandleGap> detectGapsMarketHoursAware(String symbol, String timeframe, Instant rangeStart, Instant rangeEnd) {
        List<MarketdataCandle> asc =
                candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        symbol, timeframe, rangeStart, rangeEnd);
        if (asc.isEmpty()) {
            return List.of();
        }
        long periodMs = timeframePeriodMillis(timeframe);
        Deque<Instant> expected = buildExpectedOpenTimes(rangeStart, rangeEnd, timeframe);
        if (expected.isEmpty()) {
            return List.of();
        }
        List<CandleGap> out = new ArrayList<>();
        int idx = 0;
        while (!expected.isEmpty() && idx < asc.size()) {
            Instant exp = expected.removeFirst();
            Instant actual = asc.get(idx).getOpenTime();
            if (actual.equals(exp)) {
                idx++;
                continue;
            }
            if (actual.isBefore(exp)) {
                idx++;
                continue;
            }
            Instant prev = exp.minusMillis(periodMs);
            Instant next = actual;
            long excess = Math.max(periodMs, next.toEpochMilli() - prev.toEpochMilli() - periodMs);
            out.add(new CandleGap(prev, next, excess));
            while (!expected.isEmpty() && expected.peekFirst().isBefore(actual)) {
                expected.removeFirst();
            }
            idx++;
        }
        return out;
    }

    private static long timeframePeriodMillis(String tf) {
        if (tf != null && tf.endsWith("m")) {
            return Long.parseLong(tf.substring(0, tf.length() - 1)) * 60_000L;
        }
        return 60_000L;
    }

    private Deque<Instant> buildExpectedOpenTimes(Instant start, Instant end, String timeframe) {
        Deque<Instant> out = new ArrayDeque<>();
        ZoneId marketZone = ZoneId.of(marketZoneId);
        LocalTime marketOpen = LocalTime.parse(marketOpenRaw);
        LocalTime marketClose = LocalTime.parse(marketCloseRaw);
        String tf = timeframe == null ? "1m" : timeframe.trim().toLowerCase();
        if ("1d".equals(tf)) {
            ZonedDateTime z = start.atZone(marketZone).withHour(0).withMinute(0).withSecond(0).withNano(0);
            while (z.toInstant().isBefore(end)) {
                if (isTradingDay(z.toLocalDate())) {
                    out.add(z.with(marketOpen).toInstant());
                }
                z = z.plusDays(1);
            }
            return out;
        }
        int stepMinutes = switch (tf) {
            case "5m" -> 5;
            case "15m" -> 15;
            case "1h" -> 60;
            default -> 1;
        };
        LocalDate day = start.atZone(marketZone).toLocalDate();
        LocalDate endDay = end.atZone(marketZone).toLocalDate();
        while (!day.isAfter(endDay)) {
            if (isTradingDay(day)) {
                ZonedDateTime t = day.atTime(marketOpen).atZone(marketZone);
                ZonedDateTime close = day.atTime(marketClose).atZone(marketZone);
                while (!t.isAfter(close)) {
                    Instant i = t.toInstant();
                    if (!i.isBefore(start) && !i.isAfter(end)) {
                        out.add(i);
                    }
                    t = t.plusMinutes(stepMinutes);
                }
            }
            day = day.plusDays(1);
        }
        return out;
    }

    private static boolean isTradingDay(LocalDate d) {
        return !(d.getDayOfWeek().getValue() >= 6);
    }

    public record CandleGap(Instant afterOpen, Instant nextOpen, long excessMillis) {
    }
}
