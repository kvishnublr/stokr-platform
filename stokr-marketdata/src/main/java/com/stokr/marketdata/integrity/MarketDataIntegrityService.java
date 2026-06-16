package com.stokr.marketdata.integrity;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central fail-closed market data integrity gate for live strategy evaluation.
 * Generators must not silently fall back to stale or cross-session bar windows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataIntegrityService {

    public static final String NIFTY_50_SYMBOL = "NIFTY 50";
    private static final String TIMEFRAME_1M = "1m";
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
    private static final Duration NIFTY_BAR_GAP_TOLERANCE = Duration.ofMinutes(2);
    @Value("${stokr.marketdata.integrity.mid-session-min-bars:20}")
    private int minNiftyMidSessionBars;

    private final MarketdataCandleRepository candleRepository;
    private final MarketDataIntegrityRejectionRepository rejectionRepository;
    private final MarketdataTickRepository tickRepository;
    private final OrderBookPressureTracker pressureTracker;

    @Value("${stokr.marketdata.integrity.enabled:true}")
    private boolean enabled;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.marketdata.integrity.mid-session-recovery.enabled:true}")
    private boolean midSessionRecoveryEnabled;

    @Value("${stokr.marketdata.integrity.equity-candle-max-age-seconds:300}")
    private long equityCandleMaxAgeSeconds;

    @Value("${stokr.marketdata.integrity.index-candle-max-age-seconds:300}")
    private long indexCandleMaxAgeSeconds;

    @Value("${stokr.marketdata.integrity.tick-max-age-seconds:90}")
    private long tickMaxAgeSeconds;

    @Value("${stokr.marketdata.integrity.use-live-tick-proxy-for-candle-freshness:true}")
    private boolean useLiveTickProxyForCandleFreshness;

    /** Suppress repeated DB rows for the same strategy/symbol/reason within a session window. */
    @Value("${stokr.marketdata.integrity.rejection-dedupe-seconds:300}")
    private long rejectionDedupeSeconds;

    private final ConcurrentHashMap<String, Instant> recentRejectionKeys = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns false when pre-evaluation checks fail (fail closed).
     */
    public boolean passesPreEvaluate(
            String strategyName,
            String symbol,
            Instant asOf,
            boolean requiresNiftyOpeningSession,
            boolean requiresObiTicks,
            boolean checkEquityCandleFreshness,
            boolean checkIndexCandleFreshness) {
        if (!enabled) {
            return true;
        }
        Instant anchor = asOf != null ? asOf : Instant.now();
        LocalDate sessionDate = sessionDate(anchor);

        if (requiresNiftyOpeningSession && !isNiftyOpeningSessionReady(anchor)
                && !isNiftyMidSessionRecoveryAllowed(anchor)) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.NIFTY_OPENING_INCOMPLETE,
                    null, sessionStartInstant(sessionDate), sessionDate);
            return false;
        }

        if (checkEquityCandleFreshness && !isIndexSymbol(symbol)) {
            if (!isCandleFresh(strategyName, symbol, anchor, equityCandleMaxAge(),
                    IntegrityRejectionReason.EQUITY_CANDLE_STALE, sessionDate, false)) {
                return false;
            }
        }

        if (checkIndexCandleFreshness || isIndexSymbol(symbol)) {
            String indexSymbol = isIndexSymbol(symbol) ? symbol : NIFTY_50_SYMBOL;
            if (!isCandleFresh(strategyName, indexSymbol, anchor, indexCandleMaxAge(),
                    IntegrityRejectionReason.INDEX_CANDLE_STALE, sessionDate, true)) {
                return false;
            }
        }

        if (requiresObiTicks) {
            if (!isTickFresh(strategyName, symbol, anchor, sessionDate)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true when NIFTY-dependent strategies may run for the session.
     */
    public boolean isNiftyOpeningSessionReady(Instant asOf) {
        if (!enabled) {
            return true;
        }
        Instant anchor = asOf != null ? asOf : Instant.now();
        LocalDate sessionDate = sessionDate(anchor);
        Instant sessionStart = sessionStartInstant(sessionDate);

        List<MarketdataCandle> sessionBars = candleRepository
                .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        NIFTY_50_SYMBOL,
                        TIMEFRAME_1M,
                        sessionStart,
                        anchor);

        if (sessionBars.isEmpty()) {
            return false;
        }

        Instant firstOpen = sessionBars.get(0).getOpenTime();
        if (firstOpen == null
                || !sessionDate.equals(sessionDate(firstOpen))
                || firstOpen.isAfter(sessionStart.plus(NIFTY_BAR_GAP_TOLERANCE))) {
            return false;
        }

        MarketdataCandle prev = null;
        for (MarketdataCandle bar : sessionBars) {
            if (bar.getOpenTime() == null) {
                return false;
            }
            if (!sessionDate.equals(sessionDate(bar.getOpenTime()))) {
                return false;
            }
            if (prev != null) {
                Duration gap = Duration.between(prev.getOpenTime(), bar.getOpenTime());
                if (gap.compareTo(NIFTY_BAR_GAP_TOLERANCE) > 0) {
                    return false;
                }
            }
            prev = bar;
        }
        return true;
    }

    /**
     * Allows NIFTY-dependent strategies after 09:30 IST when opening continuity was lost
     * during a feed outage but enough session bars and a fresh index candle exist.
     */
    public boolean isNiftyMidSessionRecoveryAllowed(Instant asOf) {
        if (!enabled || !midSessionRecoveryEnabled) {
            return false;
        }
        Instant anchor = asOf != null ? asOf : Instant.now();
        if (!isWithinMarketHours(anchor)) {
            return false;
        }
        if (anchor.atZone(zone).toLocalTime().isBefore(LocalTime.of(9, 30))) {
            return false;
        }
        LocalDate sessionDate = sessionDate(anchor);
        Instant sessionStart = sessionStartInstant(sessionDate);
        List<MarketdataCandle> sessionBars = candleRepository
                .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        NIFTY_50_SYMBOL, TIMEFRAME_1M, sessionStart, anchor);
        List<MarketdataCandle> tail = contiguousSessionTail(
                sessionBars, NIFTY_BAR_GAP_TOLERANCE, Math.max(10, minNiftyMidSessionBars));
        if (tail.size() < Math.max(10, minNiftyMidSessionBars)) {
            return false;
        }
        return isCandleFreshInternal(
                NIFTY_50_SYMBOL, anchor, indexCandleMaxAge(), sessionDate, true);
    }

    /** Live diagnostics for admin safety panel. */
    public Map<String, Object> diagnosticsSnapshot(Instant asOf) {
        Instant anchor = asOf != null ? asOf : Instant.now();
        Map<String, Object> m = new java.util.LinkedHashMap<>(); // LinkedHashMap for stable JSON field order
        m.put("openingSessionReady", isNiftyOpeningSessionReady(anchor));
        m.put("midSessionRecoveryAllowed", isNiftyMidSessionRecoveryAllowed(anchor));
        m.put("sessionBarCount", countSessionNiftyBars(anchor));
        m.put("midSessionMinBars", Math.max(10, minNiftyMidSessionBars));
        m.put("enabled", enabled);
        m.put("midSessionRecoveryEnabled", midSessionRecoveryEnabled);
        return m;
    }

    /**
     * Filters to current-session bars and validates lookback span. Empty = fail closed.
     */
    public Optional<List<MarketdataCandle>> validateSessionBarSeries(
            String strategyName,
            String symbol,
            List<MarketdataCandle> rawBars,
            int lookbackBars,
            LookbackWindow window,
            Instant asOf) {
        if (!enabled) {
            return Optional.ofNullable(rawBars);
        }
        if (rawBars == null || rawBars.isEmpty()) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.INSUFFICIENT_SESSION_BARS,
                    null, asOf, sessionDate(asOf));
            return Optional.empty();
        }

        Instant anchor = resolveAnchor(rawBars, asOf);
        LocalDate sessionDate = sessionDate(anchor);
        Instant sessionStart = sessionStartInstant(sessionDate);

        List<MarketdataCandle> sessionBars = new ArrayList<>();
        for (MarketdataCandle bar : rawBars) {
            if (bar.getOpenTime() == null) {
                continue;
            }
            if (!sessionDate.equals(sessionDate(bar.getOpenTime()))) {
                continue;
            }
            if (bar.getOpenTime().isBefore(sessionStart)) {
                continue;
            }
            sessionBars.add(bar);
        }
        sessionBars.sort(Comparator.comparing(MarketdataCandle::getOpenTime));

        List<MarketdataCandle> barsForEval = sessionBars;
        if (isMidSessionBarRecoveryAllowed(anchor)) {
            List<MarketdataCandle> contiguousTail = contiguousSessionTail(
                    sessionBars, NIFTY_BAR_GAP_TOLERANCE, lookbackBars + 1);
            if (contiguousTail.size() >= lookbackBars + 1) {
                barsForEval = contiguousTail;
            }
        }

        if (barsForEval.size() < lookbackBars + 1) {
            Instant latest = barsForEval.isEmpty() ? null : barsForEval.get(barsForEval.size() - 1).getOpenTime();
            recordRejection(strategyName, symbol, IntegrityRejectionReason.INSUFFICIENT_SESSION_BARS,
                    latest, sessionStart, sessionDate);
            return Optional.empty();
        }

        MarketdataCandle current = barsForEval.get(barsForEval.size() - 1);
        MarketdataCandle lookback = barsForEval.get(barsForEval.size() - 1 - lookbackBars);

        if (!sessionDate.equals(sessionDate(lookback.getOpenTime()))) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.CROSS_SESSION_LOOKBACK,
                    current.getOpenTime(), lookback.getOpenTime(), sessionDate);
            return Optional.empty();
        }

        Duration span = Duration.between(lookback.getOpenTime(), current.getOpenTime());
        Duration maxSpan = window.maxSpan();
        if (span.compareTo(maxSpan) > 0 && isMidSessionBarRecoveryAllowed(anchor)) {
            maxSpan = Duration.ofMinutes(Math.max(maxSpan.toMinutes() * 4, lookbackBars + 10L));
        }
        if (span.compareTo(maxSpan) > 0) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.TIMESTAMP_GAP_EXCEEDED,
                    current.getOpenTime(), lookback.getOpenTime(), sessionDate);
            return Optional.empty();
        }

        return Optional.of(barsForEval);
    }

    /**
     * Gap-fill may reference prior-session close, but today's session slice must be explicit.
     */
    public boolean validateGapFillSessionSlice(
            String strategyName,
            String symbol,
            List<MarketdataCandle> bars,
            int todayStartIdx,
            Instant asOf) {
        if (!enabled) {
            return true;
        }
        if (todayStartIdx < 0 || bars == null || bars.isEmpty() || todayStartIdx >= bars.size()) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.SESSION_BOUNDARY_NOT_FOUND,
                    null, sessionStartInstant(sessionDate(asOf)), sessionDate(asOf));
            return false;
        }

        Instant anchor = resolveAnchor(bars, asOf);
        LocalDate sessionDate = sessionDate(anchor);
        Instant sessionStart = sessionStartInstant(sessionDate);

        MarketdataCandle todayOpenBar = bars.get(todayStartIdx);
        if (todayOpenBar.getOpenTime() == null
                || !sessionDate.equals(sessionDate(todayOpenBar.getOpenTime()))
                || todayOpenBar.getOpenTime().isBefore(sessionStart)) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.SESSION_BOUNDARY_NOT_FOUND,
                    todayOpenBar.getOpenTime(), sessionStart, sessionDate);
            return false;
        }

        for (int i = todayStartIdx; i < bars.size(); i++) {
            Instant t = bars.get(i).getOpenTime();
            if (t == null || !sessionDate.equals(sessionDate(t))) {
                recordRejection(strategyName, symbol, IntegrityRejectionReason.CROSS_SESSION_LOOKBACK,
                        t, sessionStart, sessionDate);
                return false;
            }
        }
        return true;
    }

    @Transactional
    public void recordRejection(
            String strategyName,
            String symbol,
            IntegrityRejectionReason reason,
            Instant latestBarTime,
            Instant expectedBarTime,
            LocalDate sessionDate) {
        Instant anchor = rejectionAnchor(latestBarTime, expectedBarTime, sessionDate, zone);
        if (!isWithinMarketHours(anchor)) {
            log.debug("marketdata.integrity.reject.skipped_outside_session strategy={} symbol={} reason={}",
                    strategyName, symbol, reason.name());
            return;
        }
        if (!shouldPersistRejection(strategyName, symbol, reason, sessionDate, anchor)) {
            log.debug("marketdata.integrity.reject.deduped strategy={} symbol={} reason={}",
                    strategyName, symbol, reason.name());
            return;
        }
        log.warn("marketdata.integrity.reject strategy={} symbol={} reason={} latestBar={} expectedBar={} session={}",
                strategyName, symbol, reason.name(), latestBarTime, expectedBarTime, sessionDate);

        MarketDataIntegrityRejection row = new MarketDataIntegrityRejection();
        row.setStrategyName(strategyName != null ? strategyName : "UNKNOWN");
        row.setSymbol(symbol);
        row.setRejectionReason(reason.name());
        row.setLatestBarTime(latestBarTime);
        row.setExpectedBarTime(expectedBarTime);
        row.setSessionDate(sessionDate != null ? sessionDate : sessionDate(anchor));
        row.setCreatedAt(Instant.now());
        rejectionRepository.save(row);
    }

    private static Instant rejectionAnchor(
            Instant latestBarTime, Instant expectedBarTime, LocalDate sessionDate, ZoneId zone) {
        if (latestBarTime != null) {
            return latestBarTime;
        }
        if (expectedBarTime != null) {
            return expectedBarTime;
        }
        if (sessionDate != null) {
            return ZonedDateTime.of(sessionDate, SESSION_OPEN, zone).toInstant();
        }
        return Instant.now();
    }

    private boolean shouldPersistRejection(
            String strategyName,
            String symbol,
            IntegrityRejectionReason reason,
            LocalDate sessionDate,
            Instant anchor) {
        if (rejectionDedupeSeconds <= 0) {
            return true;
        }
        String key = (sessionDate != null ? sessionDate : sessionDate(anchor))
                + "|" + (strategyName != null ? strategyName : "UNKNOWN")
                + "|" + (symbol != null ? symbol : "*")
                + "|" + reason.name();
        Instant last = recentRejectionKeys.get(key);
        if (last != null && Duration.between(last, anchor).getSeconds() < rejectionDedupeSeconds) {
            return false;
        }
        recentRejectionKeys.put(key, anchor);
        if (recentRejectionKeys.size() > 50_000) {
            recentRejectionKeys.entrySet().removeIf(e ->
                    Duration.between(e.getValue(), anchor).getSeconds() > rejectionDedupeSeconds * 4);
        }
        return true;
    }

    public boolean isIndexSymbol(String symbol) {
        if (symbol == null) {
            return false;
        }
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        return upper.contains("NIFTY")
                || upper.contains("BANKNIFTY")
                || upper.endsWith("_FUT")
                || upper.contains(" FIN SERVICE")
                || "NIFTY 50".equalsIgnoreCase(symbol)
                || "NIFTY BANK".equalsIgnoreCase(symbol);
    }

    private boolean isCandleFresh(
            String strategyName,
            String symbol,
            Instant anchor,
            Duration maxAge,
            IntegrityRejectionReason reason,
            LocalDate sessionDate,
            boolean indexFeed) {
        if (!isCandleFreshInternal(symbol, anchor, maxAge, sessionDate, indexFeed)) {
            Optional<MarketdataCandle> latest = latestSessionCandle(symbol, sessionDate, indexFeed);
            Instant latestTime = latest.map(MarketdataCandle::getOpenTime).orElse(null);
            recordRejection(strategyName, symbol, reason, latestTime, anchor.minus(maxAge), sessionDate);
            return false;
        }
        return true;
    }

    private boolean isCandleFreshInternal(
            String symbol,
            Instant anchor,
            Duration maxAge,
            LocalDate sessionDate,
            boolean indexFeed) {
        Optional<MarketdataCandle> latest = latestSessionCandle(symbol, sessionDate, indexFeed);
        if (latest.isPresent() && latest.get().getOpenTime() != null) {
            Instant latestTime = latest.get().getOpenTime();
            if (Duration.between(latestTime, anchor).compareTo(maxAge) <= 0
                    && sessionDate.equals(sessionDate(latestTime))) {
                return true;
            }
        }
        if (useLiveTickProxyForCandleFreshness && hasFreshLiveTick(symbol, anchor, maxAge)) {
            return true;
        }
        return false;
    }

    private boolean hasFreshLiveTick(String symbol, Instant anchor, Duration maxAge) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        Instant liveTick = pressureTracker.getLastUpdate(symbol);
        if (liveTick != null && Duration.between(liveTick, anchor).compareTo(maxAge) <= 0) {
            return true;
        }
        if (isIndexSymbol(symbol)) {
            Instant niftyTick = pressureTracker.getLastUpdate(NIFTY_50_SYMBOL);
            return niftyTick != null && Duration.between(niftyTick, anchor).compareTo(maxAge) <= 0;
        }
        return false;
    }

    private Duration equityCandleMaxAge() {
        return Duration.ofSeconds(Math.max(60L, equityCandleMaxAgeSeconds));
    }

    private Duration indexCandleMaxAge() {
        return Duration.ofSeconds(Math.max(60L, indexCandleMaxAgeSeconds));
    }

    private Duration tickMaxAge() {
        return Duration.ofSeconds(Math.max(30L, tickMaxAgeSeconds));
    }

    private Optional<MarketdataCandle> latestSessionCandle(
            String symbol, LocalDate sessionDate, boolean indexFeed) {
        if (indexFeed) {
            return candleRepository
                    .findTopBySymbolAndTimeframeAndOpenTimeGreaterThanEqualAndDeletedFalseOrderByOpenTimeDesc(
                            symbol, TIMEFRAME_1M, sessionStartInstant(sessionDate));
        }
        return candleRepository
                .findTopBySymbolAndTimeframeAndOpenTimeGreaterThanEqualAndDeletedFalseOrderByOpenTimeDesc(
                        symbol, TIMEFRAME_1M, sessionStartInstant(sessionDate));
    }

    private boolean isTickFresh(String strategyName, String symbol, Instant anchor, LocalDate sessionDate) {
        Instant liveTick = pressureTracker.getLastUpdate(symbol);
        Duration tickAge = tickMaxAge();
        if (liveTick != null && Duration.between(liveTick, anchor).compareTo(tickAge) <= 0) {
            return true;
        }
        // OBI ticks are in-memory only (persist-ticks=false). A fresh session equity candle
        // means the symbol is on the live websocket feed even without order-book quantities.
        if (isCandleFreshInternal(symbol, anchor, tickAge, sessionDate, false)) {
            return true;
        }
        Optional<com.stokr.marketdata.domain.MarketdataTick> latest =
                tickRepository.findFirstBySymbolOrderByTickTimeDesc(symbol);
        if (latest.isEmpty() || latest.get().getTickTime() == null) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.TICK_FEED_STALE,
                    liveTick, anchor.minus(tickAge), sessionDate);
            return false;
        }
        Instant tickTime = latest.get().getTickTime();
        if (Duration.between(tickTime, anchor).compareTo(tickAge) > 0) {
            recordRejection(strategyName, symbol, IntegrityRejectionReason.TICK_FEED_STALE,
                    tickTime, anchor.minus(tickAge), sessionDate);
            return false;
        }
        return true;
    }

    private int countSessionNiftyBars(Instant anchor) {
        LocalDate sessionDate = sessionDate(anchor);
        Instant sessionStart = sessionStartInstant(sessionDate);
        return candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                NIFTY_50_SYMBOL, TIMEFRAME_1M, sessionStart, anchor).size();
    }

    private boolean isWithinMarketHours(Instant instant) {
        var zdt = instant.atZone(zone);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        return !t.isBefore(SESSION_OPEN) && !t.isAfter(LocalTime.of(15, 30));
    }

    /**
     * Allows lookback validation on the contiguous tail of session bars after a feed outage.
     */
    public boolean isMidSessionBarRecoveryAllowed(Instant asOf) {
        if (!enabled || !midSessionRecoveryEnabled) {
            return false;
        }
        Instant anchor = asOf != null ? asOf : Instant.now();
        if (!isWithinMarketHours(anchor)) {
            return false;
        }
        return !anchor.atZone(zone).toLocalTime().isBefore(LocalTime.of(9, 30));
    }

    private List<MarketdataCandle> contiguousSessionTail(
            List<MarketdataCandle> sessionBars, Duration maxGap, int minBars) {
        if (sessionBars == null || sessionBars.isEmpty()) {
            return List.of();
        }
        int end = sessionBars.size() - 1;
        int start = end;
        while (start > 0) {
            Duration gap = Duration.between(
                    sessionBars.get(start - 1).getOpenTime(),
                    sessionBars.get(start).getOpenTime());
            if (gap.compareTo(maxGap) > 0) {
                break;
            }
            start--;
        }
        if (end - start + 1 < minBars) {
            return List.of();
        }
        return new ArrayList<>(sessionBars.subList(start, end + 1));
    }

    private Instant resolveAnchor(List<MarketdataCandle> bars, Instant asOf) {
        if (asOf != null) {
            return asOf;
        }
        return bars.get(bars.size() - 1).getOpenTime() != null
                ? bars.get(bars.size() - 1).getOpenTime()
                : Instant.now();
    }

    private LocalDate sessionDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    private Instant sessionStartInstant(LocalDate sessionDate) {
        return ZonedDateTime.of(sessionDate, SESSION_OPEN, zone).toInstant();
    }
}
