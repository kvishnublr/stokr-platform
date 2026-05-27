package com.stokr.strategy.operational;

import com.stokr.marketdata.integrity.MarketDataIntegrityService;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocks catalog scans after restart until feed continuity and warmup bars are validated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingSafeStartupGateService {

    private static final String NIFTY = "NIFTY 50";
    private static final String TIMEFRAME = "1m";
    private static final int MIN_WARMUP_BARS = 30;

    private final MarketDataIntegrityService integrityService;
    private final MarketdataCandleRepository candleRepository;
    private final FeedHealthMonitorService feedHealthMonitorService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.safe-startup.min-warmup-seconds:120}")
    private long minWarmupSeconds;

    @Value("${stokr.strategy.safe-startup.enabled:true}")
    private boolean enabled;

    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private volatile boolean ready;
    private volatile String blockReason = "STARTUP_WARMUP";

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        startedAt.set(Instant.now());
        ready = false;
        blockReason = "STARTUP_WARMUP";
        log.info("safe_startup.init warmupSec={}", minWarmupSeconds);
    }

    public boolean isTradingReady(Instant now) {
        if (!enabled) {
            return true;
        }
        if (ready) {
            return true;
        }
        evaluate(now);
        return ready;
    }

    public Map<String, Object> snapshot(Instant now) {
        evaluate(now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ready", ready);
        m.put("blockReason", ready ? null : blockReason);
        m.put("startedAt", startedAt.get());
        m.put("minWarmupSeconds", minWarmupSeconds);
        m.put("collectedAt", now);
        return m;
    }

    private void evaluate(Instant now) {
        Instant boot = startedAt.get();
        if (boot != null && Duration.between(boot, now).getSeconds() < minWarmupSeconds) {
            blockReason = "STARTUP_WARMUP";
            return;
        }

        if (!isWithinMarketHours(now)) {
            ready = true;
            blockReason = null;
            return;
        }

        FeedHealthMonitorService.FeedHealthSnapshot feed = feedHealthMonitorService.snapshot(now);
        if (feed.equityStale() || feed.indexStale()) {
            blockReason = "FEED_NOT_FRESH";
            return;
        }

        if (!integrityService.isNiftyOpeningSessionReady(now)) {
            blockReason = "NIFTY_OPENING_INCOMPLETE";
            return;
        }

        if (!hasMinimumWarmupBars(now)) {
            blockReason = "INSUFFICIENT_WARMUP_BARS";
            return;
        }

        if (!ready) {
            log.info("safe_startup.ready — trading scans enabled");
        }
        ready = true;
        blockReason = null;
    }

    private boolean hasMinimumWarmupBars(Instant now) {
        LocalDate sessionDate = now.atZone(zone).toLocalDate();
        Instant sessionStart = ZonedDateTime.of(sessionDate, java.time.LocalTime.of(9, 15), zone).toInstant();
        var bars = candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                NIFTY, TIMEFRAME, sessionStart, now);
        return bars.size() >= MIN_WARMUP_BARS;
    }

    private boolean isWithinMarketHours(Instant now) {
        var zdt = now.atZone(zone);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        var t = zdt.toLocalTime();
        return !t.isBefore(java.time.LocalTime.of(9, 15)) && !t.isAfter(java.time.LocalTime.of(15, 30));
    }
}
