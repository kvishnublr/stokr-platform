package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.LookbackWindow;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.repository.KnnPatternEntryRepository;
import com.stokr.strategy.service.StrategyMarketIndicatorService;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvCashEquitySignalGeneratorTest {

    @Mock
    private OrderBookPressureTracker pressureTracker;
    @Mock
    private StrategyGeneratorIntegrityGate integrityGate;
    @Mock
    private StrategyMarketIndicatorService marketIndicatorService;
    @Mock
    private KnnPatternEntryRepository knnPatternRepository;

    private AdvCashEquitySignalGenerator generator;

    @BeforeEach
    void setup() {
        generator = new AdvCashEquitySignalGenerator(
                pressureTracker, integrityGate, marketIndicatorService, knnPatternRepository);
        ReflectionTestUtils.setField(generator, "zone", java.time.ZoneId.of("Asia/Kolkata"));
        ReflectionTestUtils.setField(generator, "cooldownSeconds", 0);
        ReflectionTestUtils.setField(generator, "minConsensus", 3);
        ReflectionTestUtils.setField(generator, "candidateMinConsensus", 2);
        ReflectionTestUtils.setField(generator, "candidateLogCooldownSeconds", 0);
        ReflectionTestUtils.setField(generator, "minCompositeScore", 50.0d);
    }

    @Test
    void moderateObiCountsAsSignalStrength() {
        String level = ReflectionTestUtils.invokeMethod(generator, "classifyObiLevel", 0.50d);

        assertThat(level).isEqualTo("MODERATE");
    }

    @Test
    void emitsSignalWithThreeOfFourConfirmationsWhenCompositeIsStrong() {
        Instant asOf = Instant.parse("2026-06-11T04:45:00Z"); // 10:15 IST
        StrategyContext context = new StrategyContext("BAJFINANCE", asOf, Map.of(), BigDecimal.ZERO);
        List<MarketdataCandle> bars = bars(
                "100.00", "100.10", "100.20", "100.25", "100.35",
                "100.45", "100.60", "100.75", "100.90", "101.00");

        when(integrityGate.passPreEvaluate(eq("ADV_CASH"), eq("BAJFINANCE"), eq(asOf))).thenReturn(true);
        when(integrityGate.sessionBars(eq("ADV_CASH"), eq("BAJFINANCE"), eq("1m"),
                anyInt(), anyInt(), eq(LookbackWindow.FIVE_MINUTE), any()))
                .thenReturn(Optional.of(bars));
        when(pressureTracker.getSnapshot("BAJFINANCE")).thenReturn(null);
        when(marketIndicatorService.getVix(asOf)).thenReturn(14.0d);

        StrategySignal first = generator.evaluate(context);
        StrategySignal second = generator.evaluate(context);
        StrategySignal third = generator.evaluate(context);

        assertThat(first.type()).isEqualTo(SignalType.BUY);
        assertThat(second.type()).isEqualTo(SignalType.BUY);
        assertThat(third.type()).isEqualTo(SignalType.BUY);
        assertThat(third.reason()).contains("consensus=3/4");
        assertThat(third.reason()).contains("minConsensus=3");
    }

    @Test
    void nearMissReturnsCandidateReasonInsteadOfSilentHold() {
        Instant asOf = Instant.parse("2026-06-11T04:45:00Z"); // 10:15 IST
        StrategyContext context = new StrategyContext("BAJFINANCE", asOf, Map.of(), BigDecimal.ZERO);
        List<MarketdataCandle> bars = bars(
                "100.00", "100.01", "100.02", "100.03", "100.04",
                "100.05", "100.06", "100.07", "100.08", "100.09");

        when(integrityGate.passPreEvaluate(eq("ADV_CASH"), eq("BAJFINANCE"), eq(asOf))).thenReturn(true);
        when(integrityGate.sessionBars(eq("ADV_CASH"), eq("BAJFINANCE"), eq("1m"),
                anyInt(), anyInt(), eq(LookbackWindow.FIVE_MINUTE), any()))
                .thenReturn(Optional.of(bars));
        when(pressureTracker.getSnapshot("BAJFINANCE")).thenReturn(null);
        when(marketIndicatorService.getVix(asOf)).thenReturn(14.0d);

        StrategySignal signal = generator.evaluate(context);

        assertThat(signal.type()).isEqualTo(SignalType.HOLD);
        assertThat(signal.reason()).contains("ADV_CASH_CANDIDATE");
        assertThat(signal.reason()).contains("gates=");
    }

    private static List<MarketdataCandle> bars(String... closes) {
        Instant start = Instant.parse("2026-06-11T04:36:00Z");
        return java.util.stream.IntStream.range(0, closes.length)
                .mapToObj(i -> candle(start.plusSeconds(i * 60L), closes[i], i))
                .toList();
    }

    private static MarketdataCandle candle(Instant openTime, String close, int index) {
        BigDecimal closePrice = new BigDecimal(close);
        MarketdataCandle candle = new MarketdataCandle();
        candle.setSymbol("BAJFINANCE");
        candle.setTimeframe("1m");
        candle.setOpenTime(openTime);
        candle.setOpenPrice(closePrice);
        candle.setHighPrice(closePrice.add(new BigDecimal("0.05")));
        candle.setLowPrice(closePrice.subtract(new BigDecimal("0.05")));
        candle.setClosePrice(closePrice);
        candle.setVolume(BigDecimal.valueOf(1000L + index * 100L));
        return candle;
    }
}
