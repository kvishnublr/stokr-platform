package com.stokr.intraday.detector;

import com.stokr.intraday.domain.EquitySignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ADVCashDetectorTest {

    @Mock
    private MarketDataProvider marketDataProvider;

    private ADVCashDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ADVCashDetector(marketDataProvider);
    }

    @Test
    void testDetectionOutsideTradingWindow() {
        when(marketDataProvider.isMarketOpen()).thenReturn(false);
        List<EquitySignal> signals = detector.detectSignals();
        assertTrue(signals.isEmpty());
    }

    @Test
    void testHighConfidenceSignal() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(3000);
        data.momentum5m = BigDecimal.valueOf(0.25);
        data.vixLevel = BigDecimal.valueOf(15);

        stubOpenMarket();
        stubSymbolData("RELIANCE", data);

        List<EquitySignal> signals = detector.detectSignals();

        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("RELIANCE"))
                .findFirst()
                .orElse(null);

        assertNotNull(signal);
        assertEquals(3, signal.getConfidenceLevel());
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(65)) >= 0);
    }

    @Test
    void testLowQualityRejection() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(500);
        data.momentum5m = BigDecimal.valueOf(0.02);
        data.vixLevel = BigDecimal.valueOf(32);

        stubOpenMarket();
        stubSymbolData("RELIANCE", data);

        List<EquitySignal> signals = detector.detectSignals();

        boolean hasReliance = signals.stream()
                .anyMatch(s -> s.getSymbolName().equals("RELIANCE"));

        assertFalse(hasReliance, "Low quality signal should be rejected");
    }

    @Test
    void testEntryExitLevelCalculation() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(3000);
        data.momentum5m = BigDecimal.valueOf(0.25);
        data.vixLevel = BigDecimal.valueOf(15);

        stubOpenMarket();
        stubSymbolData("RELIANCE", data);

        List<EquitySignal> signals = detector.detectSignals();
        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("RELIANCE"))
                .findFirst()
                .orElse(null);

        assertNotNull(signal);
        assertEquals("LONG", signal.getDirection());

        BigDecimal expectedT1 = BigDecimal.valueOf(1007);
        assertEquals(0, signal.getTargetLevel1().compareTo(expectedT1));

        BigDecimal expectedT2 = BigDecimal.valueOf(1014);
        assertEquals(0, signal.getTargetLevel2().compareTo(expectedT2));

        BigDecimal expectedSL = BigDecimal.valueOf(996);
        assertEquals(0, signal.getStopLossLevel().compareTo(expectedSL));
    }

    @Test
    void testQualityScoreCalculation() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(5000);
        data.momentum5m = BigDecimal.valueOf(0.3);
        data.vixLevel = BigDecimal.valueOf(12);

        stubOpenMarket();
        stubSymbolData("TCS", data);

        List<EquitySignal> signals = detector.detectSignals();
        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("TCS"))
                .findFirst()
                .orElse(null);

        assertNotNull(signal);
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(85)) > 0);
    }

    @Test
    void testVixFiltering() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(3000);
        data.momentum5m = BigDecimal.valueOf(0.25);
        data.vixLevel = BigDecimal.valueOf(40);

        stubOpenMarket();
        stubSymbolData("HDFCBANK", data);

        List<EquitySignal> signals = detector.detectSignals();

        boolean hasSignal = signals.stream()
                .anyMatch(s -> s.getSymbolName().equals("HDFCBANK"));

        assertFalse(hasSignal, "Signal with VIX outside 8-35 should be rejected");
    }

    @Test
    void testSectorAssignment() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(3000);
        data.momentum5m = BigDecimal.valueOf(0.25);
        data.vixLevel = BigDecimal.valueOf(15);

        stubOpenMarket();
        stubSymbolData("INFY", data);

        List<EquitySignal> signals = detector.detectSignals();
        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("INFY"))
                .findFirst()
                .orElse(null);

        assertNotNull(signal);
        assertEquals("IT", signal.getSector());
    }

    @Test
    void testTierClassification() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(3000);
        data.momentum5m = BigDecimal.valueOf(0.25);
        data.vixLevel = BigDecimal.valueOf(15);

        stubOpenMarket();
        stubSymbolData("PNB", data);

        List<EquitySignal> signals = detector.detectSignals();
        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("PNB"))
                .findFirst()
                .orElse(null);

        assertNotNull(signal);
        assertEquals("TIER3", signal.getTier());
    }

    @Test
    void testDirectionAlignment() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(3000);
        data.momentum5m = BigDecimal.valueOf(0.25);
        data.vixLevel = BigDecimal.valueOf(15);

        stubOpenMarket();
        stubSymbolData("MARUTI", data);

        List<EquitySignal> signals = detector.detectSignals();
        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("MARUTI"))
                .findFirst()
                .orElse(null);

        assertNotNull(signal);
        assertEquals("LONG", signal.getDirection());
    }

    @Test
    void testMinQualityThreshold() {
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(1000);
        data.volume5m = BigDecimal.valueOf(1200);
        data.momentum5m = BigDecimal.valueOf(0.11);
        data.vixLevel = BigDecimal.valueOf(24);

        stubOpenMarket();
        stubSymbolData("WIPRO", data);

        List<EquitySignal> signals = detector.detectSignals();

        EquitySignal signal = signals.stream()
                .filter(s -> s.getSymbolName().equals("WIPRO"))
                .findFirst()
                .orElse(null);

        if (signal != null) {
            assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(65)) >= 0);
        }
    }

    private void stubOpenMarket() {
        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630);
    }

    private void stubSymbolData(String symbol, MarketDataProvider.IndexMarketData data) {
        when(marketDataProvider.getIndexMarketData(anyString())).thenReturn(null);
        when(marketDataProvider.getIndexMarketData(symbol)).thenReturn(data);
    }
}
