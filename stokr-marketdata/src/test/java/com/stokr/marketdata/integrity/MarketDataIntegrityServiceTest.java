package com.stokr.marketdata.integrity;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataIntegrityServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 27);

    @Mock
    private MarketdataCandleRepository candleRepository;

    @Mock
    private MarketDataIntegrityRejectionRepository rejectionRepository;

    @Mock
    private MarketdataTickRepository tickRepository;

    @Mock
    private OrderBookPressureTracker pressureTracker;

    private MarketDataIntegrityService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataIntegrityService(
                candleRepository, rejectionRepository, tickRepository, pressureTracker);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "zone", ZONE);
    }

    @Test
    void rejectsInsufficientSessionBarsWhenPriorDayHistoryPresent() {
        Instant asOf = ist(SESSION, 10, 30);
        List<MarketdataCandle> raw = new ArrayList<>();
        raw.add(candle(ist(SESSION.minusDays(1), 15, 25)));
        for (int minute = 15; minute <= 30; minute++) {
            raw.add(candle(ist(SESSION, 10, minute)));
        }

        Optional<List<MarketdataCandle>> result = service.validateSessionBarSeries(
                "SECTOR_LAGGARD", "RELIANCE", raw, 30, LookbackWindow.THIRTY_MINUTE, asOf);

        assertTrue(result.isEmpty());
        verifyRejection(IntegrityRejectionReason.INSUFFICIENT_SESSION_BARS);
    }

    @Test
    void rejectsWhenLookbackSpanExceedsThirtyMinuteWindow() {
        Instant asOf = ist(SESSION, 10, 30);
        List<MarketdataCandle> sessionBars = new ArrayList<>();
        sessionBars.add(candle(ist(SESSION, 9, 15)));
        for (int minute = 16; minute <= 44; minute++) {
            sessionBars.add(candle(ist(SESSION, 9, minute)));
        }
        sessionBars.add(candle(ist(SESSION, 10, 30)));

        Optional<List<MarketdataCandle>> result = service.validateSessionBarSeries(
                "SECTOR_LAGGARD", "RELIANCE", sessionBars, 30, LookbackWindow.THIRTY_MINUTE, asOf);

        assertTrue(result.isEmpty());
        verifyRejection(IntegrityRejectionReason.TIMESTAMP_GAP_EXCEEDED);
    }

    @Test
    void niftyOpeningIncompleteWhenFirstBarMissingFrom915() {
        Instant asOf = ist(SESSION, 10, 30);
        when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                eq(MarketDataIntegrityService.NIFTY_50_SYMBOL),
                eq("1m"),
                any(Instant.class),
                eq(asOf)))
                .thenReturn(List.of(candle(ist(SESSION, 10, 5))));

        assertFalse(service.isNiftyOpeningSessionReady(asOf));
    }

    @Test
    void allowsContiguousTailLookbackAfterMidSessionFeedGap() {
        ReflectionTestUtils.setField(service, "midSessionRecoveryEnabled", true);
        Instant asOf = ist(SESSION, 12, 30);
        List<MarketdataCandle> sessionBars = new ArrayList<>();
        for (int minute = 15; minute <= 30; minute++) {
            sessionBars.add(candle(ist(SESSION, 9, minute)));
        }
        for (int minute = 0; minute <= 30; minute++) {
            sessionBars.add(candle(ist(SESSION, 12, minute)));
        }

        Optional<List<MarketdataCandle>> result = service.validateSessionBarSeries(
                "SECTOR_LAGGARD", "RELIANCE", sessionBars, 30, LookbackWindow.THIRTY_MINUTE, asOf);

        assertFalse(result.isEmpty());
        assertTrue(result.get().size() >= 31);
    }

    @Test
    void niftyOpeningReadyWhenBarsContinuousFrom915() {
        Instant asOf = ist(SESSION, 10, 5);
        List<MarketdataCandle> bars = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            bars.add(candle(ist(SESSION, 9, 15).plusSeconds(i * 60L)));
        }
        when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                eq(MarketDataIntegrityService.NIFTY_50_SYMBOL),
                eq("1m"),
                any(Instant.class),
                eq(asOf)))
                .thenReturn(bars);

        assertTrue(service.isNiftyOpeningSessionReady(asOf));
    }

    private void verifyRejection(IntegrityRejectionReason reason) {
        ArgumentCaptor<MarketDataIntegrityRejection> captor =
                ArgumentCaptor.forClass(MarketDataIntegrityRejection.class);
        verify(rejectionRepository).save(captor.capture());
        assertTrue(captor.getValue().getRejectionReason().contains(reason.name()));
    }

    private static Instant ist(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), ZONE).toInstant();
    }

    private static MarketdataCandle candle(Instant openTime) {
        MarketdataCandle candle = new MarketdataCandle();
        candle.setOpenTime(openTime);
        candle.setOpenPrice(BigDecimal.valueOf(100));
        candle.setHighPrice(BigDecimal.valueOf(101));
        candle.setLowPrice(BigDecimal.valueOf(99));
        candle.setClosePrice(BigDecimal.valueOf(100.5));
        candle.setVolume(BigDecimal.valueOf(1000));
        return candle;
    }
}
