package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.ExitDecision;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryService;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PressureSmartExitServiceTest {

    @Mock
    private StrategySignalRepository signalRepository;
    @Mock
    private OrderBookPressureTracker pressureTracker;
    @Mock
    private MarketDataQueryService marketDataQueryService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private StrategyExitTelemetryService exitTelemetryService;
    @Mock
    private SignalOutcomeTrackerService signalOutcomeTrackerService;

    private InstrumentNormalizationService instrumentNormalizationService;
    private PressureSmartExitService service;

    @BeforeEach
    void setUp() {
        instrumentNormalizationService = new InstrumentNormalizationService();
        service = new PressureSmartExitService(
                signalRepository,
                pressureTracker,
                marketDataQueryService,
                eventPublisher,
                exitTelemetryService,
                signalOutcomeTrackerService,
                instrumentNormalizationService
        );

        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "emergencyVolumeVacuumRatio", 0.10d);
        ReflectionTestUtils.setField(service, "emergencyCurrentBarMinAgeSeconds", 55L);
        ReflectionTestUtils.setField(service, "emergencyMinHoldSeconds", 180L);
        ReflectionTestUtils.setField(service, "emergencyBarRangePct", 99d); // disable bar-range emergency for this test
        ReflectionTestUtils.setField(service, "emergencyCandleStaleSeconds", 999999L); // disable feed-stale

        doNothing().when(signalOutcomeTrackerService).evaluateSingleSignal(any(StrategySignalEntity.class), any(Instant.class));
        when(pressureTracker.getSnapshot(anyString())).thenReturn(null);
        when(pressureTracker.analyze(anyString(), anyInt())).thenReturn(null);
    }

    @Test
    void volumeVacuumDoesNotFireOnYoungBar() {
        Instant now = Instant.parse("2026-05-29T04:00:09Z");
        StrategySignalEntity sig = baseSignal(now.minusSeconds(240));

        List<MarketdataCandle> bars = candles(now.minusSeconds(9), 12, 1_425_988d, 3_692d);
        when(marketDataQueryService.lastBarsAsc(eq("ITC"), eq("1m"), eq(12))).thenReturn(bars);

        ExitDecision decision = ReflectionTestUtils.invokeMethod(service, "evaluateExit", sig, now);
        assertNull(decision);
    }

    @Test
    void volumeVacuumFiresOnlyWhenOldEnoughAndAfterMinHold() {
        Instant now = Instant.parse("2026-05-29T04:00:58Z");
        StrategySignalEntity sig = baseSignal(now.minusSeconds(240));

        // last bar open 58s ago => age=58, expectedVol ~ 0.96*avg, currentVol tiny => vacuum
        List<MarketdataCandle> bars = candles(now.minusSeconds(58), 12, 1_425_988d, 3_692d);
        when(marketDataQueryService.lastBarsAsc(eq("ITC"), eq("1m"), eq(12))).thenReturn(bars);

        ExitDecision decision = ReflectionTestUtils.invokeMethod(service, "evaluateExit", sig, now);
        assertNotNull(decision);
    }

    @Test
    void volumeVacuumRespectsEmergencyMinHold() {
        Instant now = Instant.parse("2026-05-29T04:00:58Z");
        StrategySignalEntity sig = baseSignal(now.minusSeconds(30)); // hold < 180s

        List<MarketdataCandle> bars = candles(now.minusSeconds(58), 12, 1_425_988d, 3_692d);
        when(marketDataQueryService.lastBarsAsc(eq("ITC"), eq("1m"), eq(12))).thenReturn(bars);

        ExitDecision decision = ReflectionTestUtils.invokeMethod(service, "evaluateExit", sig, now);
        assertNull(decision);
    }

    private static StrategySignalEntity baseSignal(Instant createdAt) {
        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setCreatedAt(createdAt);
        sig.setStrategyName("ADV_CASH");
        sig.setSymbol("NSE:ITC");
        sig.setSignalType(SignalType.BUY);
        sig.setEntryReferencePrice(BigDecimal.valueOf(288.00));
        sig.setStopPrice(BigDecimal.valueOf(286.00));
        sig.setTargetPrice(BigDecimal.valueOf(292.00));
        sig.setOutcomeStatus("RUNNING");
        return sig;
    }

    private static List<MarketdataCandle> candles(Instant lastOpenTime, int count, double avgVol, double currentVol) {
        List<MarketdataCandle> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            MarketdataCandle c = new MarketdataCandle();
            c.setSymbol("ITC");
            c.setTimeframe("1m");
            Instant open = (i == 0) ? lastOpenTime : lastOpenTime.minusSeconds((long) i * 60L);
            c.setOpenTime(open);
            c.setOpenPrice(BigDecimal.valueOf(288.0));
            c.setHighPrice(BigDecimal.valueOf(288.2));
            c.setLowPrice(BigDecimal.valueOf(287.8));
            c.setClosePrice(BigDecimal.valueOf(288.0));
            c.setVolume(BigDecimal.valueOf(i == 0 ? currentVol : avgVol));
            out.add(c);
        }
        // MarketDataQueryService returns asc by openTime; our open times are already ascending.
        out.sort(java.util.Comparator.comparing(MarketdataCandle::getOpenTime));
        return out;
    }
}

