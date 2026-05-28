package com.stokr.marketdata.monitor;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedHealthMonitorService {

    private static final String NIFTY_50 = "NIFTY 50";
    private static final String NIFTY_FUT = "NIFTY_FUT";
    private static final String TIMEFRAME_1M = "1m";

    private final MarketdataCandleRepository candleRepository;
    private final MarketdataTickRepository tickRepository;
    private final OrderBookPressureTracker pressureTracker;
    private final FeedHealthWebSocketState webSocketState;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.marketdata.feed-health.equity-stale-seconds:120}")
    private long equityStaleSeconds;

    @Value("${stokr.marketdata.feed-health.index-stale-seconds:120}")
    private long indexStaleSeconds;

    @Value("${stokr.marketdata.feed-health.tick-stale-seconds:30}")
    private long tickStaleSeconds;

    @Value("${stokr.marketdata.feed-health.outage-error-seconds:300}")
    private long outageErrorSeconds;

    private final AtomicReference<Instant> lastEquityCandle = new AtomicReference<>();
    private final AtomicReference<Instant> lastIndexCandle = new AtomicReference<>();
    private final AtomicReference<Instant> lastFuturesCandle = new AtomicReference<>();
    private final AtomicReference<Instant> lastTick = new AtomicReference<>();
    private final AtomicLong staleFeedIncidents = new AtomicLong();
    private final AtomicLong totalOutageSeconds = new AtomicLong();
    private volatile Instant outageStartedAt;
    private volatile FeedHealthLevel lastLevel = FeedHealthLevel.OK;

    public record FeedHealthSnapshot(
            Instant latestEquityCandle,
            Instant latestIndexCandle,
            Instant latestFuturesCandle,
            Instant latestTick,
            boolean websocketConnected,
            int reconnectAttempts,
            long equityGapSeconds,
            long indexGapSeconds,
            long futuresGapSeconds,
            long tickGapSeconds,
            boolean equityStale,
            boolean indexStale,
            boolean tickStale,
            long staleFeedIncidents,
            long totalOutageSeconds,
            FeedHealthLevel level
    ) {}

    public enum FeedHealthLevel {
        OK, WARN, ERROR
    }

    @Scheduled(fixedDelayString = "${stokr.marketdata.feed-health.poll-ms:30000}")
    public void monitor() {
        Instant now = Instant.now();
        FeedHealthSnapshot snapshot = refreshAndEvaluate(now);
        emitLogs(snapshot, now);
    }

    public FeedHealthSnapshot snapshot(Instant now) {
        return refreshAndEvaluate(now);
    }

    private FeedHealthSnapshot refreshAndEvaluate(Instant now) {
        Instant equity = latestCandleTime(null);
        Instant index = latestCandleTime(NIFTY_50);
        Instant futures = latestCandleTime(NIFTY_FUT);
        Instant tick = tickRepository.findFirstByOrderByTickTimeDesc()
                .map(t -> t.getTickTime())
                .orElse(null);
        if (tick == null) {
            // Ticks are in-memory by default (persist-ticks=false); use latest equity candle as proxy.
            tick = equity;
        }

        lastEquityCandle.set(equity);
        lastIndexCandle.set(index);
        lastFuturesCandle.set(futures);
        lastTick.set(tick);

        long equityGap = gapSeconds(equity, now);
        long indexGap = gapSeconds(index, now);
        long futuresGap = gapSeconds(futures, now);
        long tickGap = gapSeconds(tick, now);

        boolean marketHours = isMarketHours(now);
        boolean equityStale = marketHours && equityGap > equityStaleSeconds;
        boolean indexStale = marketHours && indexGap > indexStaleSeconds;
        if (indexStale && !equityStale && equityGap <= equityStaleSeconds && indexGap <= 600) {
            indexStale = false;
        }
        boolean tickStale = marketHours && tickGap > tickStaleSeconds;

        FeedHealthLevel level = FeedHealthLevel.OK;
        if (equityStale || indexStale) {
            level = FeedHealthLevel.WARN;
            if (lastLevel == FeedHealthLevel.OK) {
                staleFeedIncidents.incrementAndGet();
            }
        }
        long worstGap = Math.max(equityGap, Math.max(indexGap, futuresGap));
        if (marketHours && worstGap > outageErrorSeconds) {
            level = FeedHealthLevel.ERROR;
            if (outageStartedAt == null) {
                outageStartedAt = now;
            }
        } else if (outageStartedAt != null) {
            totalOutageSeconds.addAndGet(Duration.between(outageStartedAt, now).getSeconds());
            outageStartedAt = null;
            if (webSocketState.isConnected()) {
                log.info("feed.health.recovered equityGapSec={} indexGapSec={} reconnectAttempts={}",
                        equityGap, indexGap, webSocketState.reconnectAttempts());
                webSocketState.resetReconnectAttempts();
            }
        }
        lastLevel = level;

        return new FeedHealthSnapshot(
                equity, index, futures, tick,
                webSocketState.isConnected(),
                webSocketState.reconnectAttempts(),
                equityGap, indexGap, futuresGap, tickGap,
                equityStale, indexStale, tickStale,
                staleFeedIncidents.get(),
                totalOutageSeconds.get(),
                level
        );
    }

    private void emitLogs(FeedHealthSnapshot snapshot, Instant now) {
        if (!isMarketHours(now)) {
            return;
        }
        switch (snapshot.level()) {
            case WARN -> log.warn(
                    "feed.health.stale equityGapSec={} indexGapSec={} tickGapSec={} wsConnected={}",
                    snapshot.equityGapSeconds(), snapshot.indexGapSeconds(), snapshot.tickGapSeconds(),
                    snapshot.websocketConnected());
            case ERROR -> log.error(
                    "feed.health.outage equityGapSec={} indexGapSec={} wsConnected={} reconnectAttempts={}",
                    snapshot.equityGapSeconds(), snapshot.indexGapSeconds(),
                    snapshot.websocketConnected(), snapshot.reconnectAttempts());
            default -> { }
        }
    }

    public Map<String, Object> snapshotMap(Instant now) {
        FeedHealthSnapshot s = snapshot(now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("latestEquityCandle", s.latestEquityCandle());
        m.put("latestIndexCandle", s.latestIndexCandle());
        m.put("latestFuturesCandle", s.latestFuturesCandle());
        m.put("latestTick", s.latestTick());
        m.put("websocketConnected", s.websocketConnected());
        m.put("reconnectAttempts", s.reconnectAttempts());
        m.put("equityGapSeconds", s.equityGapSeconds());
        m.put("indexGapSeconds", s.indexGapSeconds());
        m.put("futuresGapSeconds", s.futuresGapSeconds());
        m.put("tickGapSeconds", s.tickGapSeconds());
        m.put("equityStale", s.equityStale());
        m.put("indexStale", s.indexStale());
        m.put("tickStale", s.tickStale());
        m.put("staleFeedIncidents", s.staleFeedIncidents());
        m.put("totalOutageSeconds", s.totalOutageSeconds());
        m.put("level", s.level().name());
        m.put("collectedAt", now);
        return m;
    }

    public long staleFeedIncidents() {
        return staleFeedIncidents.get();
    }

    public long totalOutageSeconds() {
        long base = totalOutageSeconds.get();
        if (outageStartedAt != null) {
            base += Duration.between(outageStartedAt, Instant.now()).getSeconds();
        }
        return base;
    }

    private Instant latestCandleTime(String symbol) {
        if (symbol == null) {
            return candleRepository.findLatestEquityCandleOpenTime();
        }
        Instant sessionStart = sessionStartInstant(sessionDate(Instant.now()));
        Optional<MarketdataCandle> sessionLatest = candleRepository
                .findTopBySymbolAndTimeframeAndOpenTimeGreaterThanEqualAndDeletedFalseOrderByOpenTimeDesc(
                        symbol, TIMEFRAME_1M, sessionStart);
        Instant candleTime = sessionLatest.map(MarketdataCandle::getOpenTime).orElse(null);
        if (NIFTY_50.equals(symbol)) {
            Instant pressureTick = pressureTracker.getLastUpdate(NIFTY_50);
            return maxInstant(candleTime, pressureTick);
        }
        return candleTime;
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private LocalDate sessionDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    private Instant sessionStartInstant(LocalDate sessionDate) {
        return ZonedDateTime.of(sessionDate, LocalTime.of(9, 15), zone).toInstant();
    }

    private static long gapSeconds(Instant timestamp, Instant now) {
        if (timestamp == null) {
            return Long.MAX_VALUE / 2;
        }
        return Math.max(0, Duration.between(timestamp, now).getSeconds());
    }

    private boolean isMarketHours(Instant now) {
        var zdt = now.atZone(zone);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }
}
