package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrencyGeneratedSignalGeneratorTest {

    private static final Instant AS_OF = Instant.parse("2026-06-11T04:30:00Z");

    @Mock
    private MarketDataQueryService marketDataQueryService;

    @Mock
    private StrategyGeneratorIntegrityGate integrityGate;

    @Test
    void usdinrMomentumSignalCarriesEntryStopAndTarget() {
        UsdInrMomentumSignalGenerator generator =
                new UsdInrMomentumSignalGenerator(marketDataQueryService, integrityGate);
        ReflectionTestUtils.setField(generator, "minMomentumPct", 0.03);
        ReflectionTestUtils.setField(generator, "stopLossPct", 0.80);
        ReflectionTestUtils.setField(generator, "profitTargetPct", 1.50);
        ReflectionTestUtils.setField(generator, "cooldownSeconds", 900);

        String symbol = "USDINR24JUNFUT";
        when(integrityGate.passPreEvaluate(eq("USDINR_MOMENTUM"), eq(symbol), eq(AS_OF))).thenReturn(true);
        when(marketDataQueryService.lastBarsAsc(eq(symbol), eq("5m"), eq(12)))
                .thenReturn(bars(symbol, 83.00, 83.01, 83.02, 83.03, 83.04, 83.05,
                        83.06, 83.07, 83.08, 83.16, 83.24, 83.32));

        StrategySignal signal = generator.evaluate(context(symbol));

        assertEquals(SignalType.BUY, signal.type());
        assertNotNull(signal.entryPrice());
        assertNotNull(signal.stopPrice());
        assertNotNull(signal.targetPrice());
        assertTrue(signal.stopPrice().compareTo(signal.entryPrice()) < 0);
        assertTrue(signal.targetPrice().compareTo(signal.entryPrice()) > 0);
    }

    @Test
    void eurinrMeanReversionSignalCarriesEntryStopAndTarget() {
        EurInrMeanReversionSignalGenerator generator =
                new EurInrMeanReversionSignalGenerator(marketDataQueryService, integrityGate);
        ReflectionTestUtils.setField(generator, "stretchPct", 0.06);
        ReflectionTestUtils.setField(generator, "stopLossPct", 0.80);
        ReflectionTestUtils.setField(generator, "profitTargetPct", 1.50);
        ReflectionTestUtils.setField(generator, "cooldownSeconds", 900);

        String symbol = "EURINR24JUNFUT";
        when(integrityGate.passPreEvaluate(eq("EURINR_MEAN_REVERSION"), eq(symbol), eq(AS_OF))).thenReturn(true);
        when(marketDataQueryService.lastBarsAsc(eq(symbol), eq("5m"), eq(20)))
                .thenReturn(bars(symbol, 90.00, 90.00, 90.00, 90.00, 90.00,
                        90.00, 90.00, 90.00, 90.00, 90.00,
                        89.95, 89.90, 89.85, 89.80, 89.75,
                        89.70, 89.65, 89.60, 89.55, 89.50));

        StrategySignal signal = generator.evaluate(context(symbol));

        assertEquals(SignalType.BUY, signal.type());
        assertNotNull(signal.entryPrice());
        assertNotNull(signal.stopPrice());
        assertNotNull(signal.targetPrice());
        assertTrue(signal.stopPrice().compareTo(signal.entryPrice()) < 0);
        assertTrue(signal.targetPrice().compareTo(signal.entryPrice()) > 0);
    }

    private static StrategyContext context(String symbol) {
        return new StrategyContext(symbol, AS_OF, Map.of(), null);
    }

    private static List<MarketdataCandle> bars(String symbol, double... closes) {
        List<MarketdataCandle> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            BigDecimal close = BigDecimal.valueOf(closes[i]);
            MarketdataCandle candle = new MarketdataCandle();
            candle.setSymbol(symbol);
            candle.setTimeframe("5m");
            candle.setOpenTime(AS_OF.minusSeconds((long) (closes.length - i) * 300));
            candle.setOpenPrice(close.subtract(BigDecimal.valueOf(0.01)));
            candle.setHighPrice(close.add(BigDecimal.valueOf(0.02)));
            candle.setLowPrice(close.subtract(BigDecimal.valueOf(0.02)));
            candle.setClosePrice(close);
            candle.setVolume(BigDecimal.valueOf(100000));
            out.add(candle);
        }
        return out;
    }
}
