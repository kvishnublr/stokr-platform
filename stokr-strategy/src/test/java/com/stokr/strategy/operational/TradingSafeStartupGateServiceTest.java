package com.stokr.strategy.operational;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.MarketDataIntegrityService;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingSafeStartupGateServiceTest {

    @Mock
    private MarketDataIntegrityService integrityService;
    @Mock
    private MarketdataCandleRepository candleRepository;
    @Mock
    private FeedHealthMonitorService feedHealthMonitorService;

    private TradingSafeStartupGateService gate;

    @BeforeEach
    void setUp() {
        gate = new TradingSafeStartupGateService(integrityService, candleRepository, feedHealthMonitorService);
        ReflectionTestUtils.setField(gate, "zone", ZoneId.of("Asia/Kolkata"));
        ReflectionTestUtils.setField(gate, "minWarmupSeconds", 0L);
        ReflectionTestUtils.setField(gate, "enabled", true);
        gate.onReady();
    }

    @Test
    void blocksWhenFeedStaleDuringMarketHours() {
        Instant now = ZonedDateTime.of(2026, 5, 27, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
        when(feedHealthMonitorService.snapshot(now)).thenReturn(staleFeedSnapshot());

        assertFalse(gate.isTradingReady(now));
    }

    @Test
    void readyWhenFeedFreshIntegrityOkAndWarmupBarsPresent() {
        Instant now = ZonedDateTime.of(2026, 5, 27, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
        when(feedHealthMonitorService.snapshot(now)).thenReturn(freshFeedSnapshot());
        when(integrityService.isNiftyOpeningSessionReady(now)).thenReturn(true);
        when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                eq("NIFTY 50"), eq("1m"), any(), eq(now)))
                .thenReturn(warmupBars(30));

        assertTrue(gate.isTradingReady(now));
        assertTrue(gate.isTradingReady(now));
    }

    @Test
    void midSessionRecoveryWhenOpeningGapsButFeedFreshAndEnoughBars() {
        Instant now = ZonedDateTime.of(2026, 5, 28, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
        when(feedHealthMonitorService.snapshot(now)).thenReturn(freshFeedSnapshot());
        when(integrityService.isNiftyOpeningSessionReady(now)).thenReturn(false);
        when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                eq("NIFTY 50"), eq("1m"), any(), eq(now)))
                .thenReturn(warmupBars(35));

        assertTrue(gate.isTradingReady(now));
    }

    @Test
    void blocksWhenOpeningIncompleteBefore930() {
        Instant now = ZonedDateTime.of(2026, 5, 28, 9, 20, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
        when(feedHealthMonitorService.snapshot(now)).thenReturn(freshFeedSnapshot());
        when(integrityService.isNiftyOpeningSessionReady(now)).thenReturn(false);

        assertFalse(gate.isTradingReady(now));
    }

    @Test
    void disabledGateAlwaysReady() {
        ReflectionTestUtils.setField(gate, "enabled", false);
        assertTrue(gate.isTradingReady(Instant.now()));
    }

    private static FeedHealthMonitorService.FeedHealthSnapshot staleFeedSnapshot() {
        return new FeedHealthMonitorService.FeedHealthSnapshot(
                Instant.now(), Instant.now(), Instant.now(), Instant.now(),
                true, 0, 300, 300, 300, 300,
                true, true, false, 1, 0, FeedHealthMonitorService.FeedHealthLevel.WARN);
    }

    private static FeedHealthMonitorService.FeedHealthSnapshot freshFeedSnapshot() {
        Instant now = Instant.now();
        return new FeedHealthMonitorService.FeedHealthSnapshot(
                now, now, now, now,
                true, 0, 30, 30, 30, 30,
                false, false, false, 0, 0, FeedHealthMonitorService.FeedHealthLevel.OK);
    }

    private static List<MarketdataCandle> warmupBars(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        MarketdataCandle candle = new MarketdataCandle();
        return Collections.nCopies(count, candle);
    }
}
