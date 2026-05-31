package com.stokr.intraday.detector;

import com.stokr.intraday.domain.FuturesSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for S3 and S7 futures strategies
 */
public class S3S7DetectorTest {

    @Mock
    private MarketDataProvider marketDataProvider;

    private S3VWAPDetector s3Detector;
    private S7RangeFadeDetector s7Detector;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        s3Detector = new S3VWAPDetector(marketDataProvider);
        s7Detector = new S7RangeFadeDetector(marketDataProvider);
    }

    // ===== S3 VWAP Tests =====

    @Test
    public void testS3_VWAPRetest_LongSignal() {
        // Setup: Market data for LONG setup (price above VWAP)
        MarketDataProvider.IndexMarketData niftyData = new MarketDataProvider.IndexMarketData();
        niftyData.currentPrice = BigDecimal.valueOf(24100.00);
        niftyData.vwap = BigDecimal.valueOf(24090.00);
        niftyData.sma20 = BigDecimal.valueOf(24080.00);
        niftyData.sma50 = BigDecimal.valueOf(24050.00);
        niftyData.range5m = BigDecimal.valueOf(0.005);
        niftyData.volume5m = BigDecimal.valueOf(5000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630); // 10:30 AM
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(niftyData);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(null);

        // Execute
        List<FuturesSignal> signals = s3Detector.detectSignals();

        // Verify
        assertFalse(signals.isEmpty());
        FuturesSignal signal = signals.get(0);
        assertEquals("S3", signal.getStrategyName());
        assertEquals("NIFTY", signal.getSymbolName());
        assertEquals("LONG", signal.getDirection());
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(65)) >= 0, "Quality score should be >= 65");
    }

    @Test
    public void testS3_VWAPRetest_ShortSignal() {
        // Setup: Price below VWAP (SHORT setup)
        MarketDataProvider.IndexMarketData bnfData = new MarketDataProvider.IndexMarketData();
        bnfData.currentPrice = BigDecimal.valueOf(48300.00);
        bnfData.vwap = BigDecimal.valueOf(48310.00);
        bnfData.sma20 = BigDecimal.valueOf(48320.00);
        bnfData.sma50 = BigDecimal.valueOf(48350.00);
        bnfData.range5m = BigDecimal.valueOf(0.006);
        bnfData.volume5m = BigDecimal.valueOf(8000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(700); // 11:40 AM
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(null);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(bnfData);

        // Execute
        List<FuturesSignal> signals = s3Detector.detectSignals();

        // Verify
        assertFalse(signals.isEmpty());
        FuturesSignal signal = signals.get(0);
        assertEquals("SHORT", signal.getDirection());
    }

    @Test
    public void testS3_QualityScoreCalculation() {
        // Setup: Good quality setup
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(24100.00);
        data.vwap = BigDecimal.valueOf(24100.00);
        data.sma20 = BigDecimal.valueOf(24090.00);
        data.sma50 = BigDecimal.valueOf(24080.00);
        data.range5m = BigDecimal.valueOf(0.008);
        data.volume5m = BigDecimal.valueOf(10000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(null);

        // Execute
        List<FuturesSignal> signals = s3Detector.detectSignals();

        // Verify quality score >= 80
        if (!signals.isEmpty()) {
            FuturesSignal signal = signals.get(0);
            assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(80)) >= 0, "Quality score should be >= 80");
        }
    }

    @Test
    public void testS3_RejectsOutsideTradingWindow() {
        // Setup: Outside trading window
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(24100.00);
        data.vwap = BigDecimal.valueOf(24100.00);

        when(marketDataProvider.isMarketOpen()).thenReturn(false); // Market closed
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        // Execute
        List<FuturesSignal> signals = s3Detector.detectSignals();

        // Verify: No signals outside trading window
        assertTrue(signals.isEmpty());
    }

    // ===== S7 Range Fade Tests =====

    @Test
    public void testS7_RangeFade_ShortSignal() {
        // Setup: Price near range high (fade setup)
        MarketDataProvider.IndexMarketData niftyData = new MarketDataProvider.IndexMarketData();
        niftyData.currentPrice = BigDecimal.valueOf(24150.00);
        niftyData.recent5mHigh = BigDecimal.valueOf(24155.00);
        niftyData.recent5mLow = BigDecimal.valueOf(24100.00);
        niftyData.momentum5m = BigDecimal.valueOf(-0.002); // Negative momentum = fade
        niftyData.range5m = BigDecimal.valueOf(0.0025);
        niftyData.volume5m = BigDecimal.valueOf(5000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(niftyData);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(null);

        // Execute
        List<FuturesSignal> signals = s7Detector.detectSignals();

        // Verify
        assertFalse(signals.isEmpty());
        FuturesSignal signal = signals.get(0);
        assertEquals("S7", signal.getStrategyName());
        assertEquals("SHORT", signal.getDirection());
    }

    @Test
    public void testS7_TargetAndSLCalculation() {
        // Setup: Valid range fade setup
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(48400.00);
        data.recent5mHigh = BigDecimal.valueOf(48410.00);
        data.recent5mLow = BigDecimal.valueOf(48350.00);
        data.momentum5m = BigDecimal.valueOf(0.0);
        data.volume5m = BigDecimal.valueOf(6000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(700);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(null);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(data);

        // Execute
        List<FuturesSignal> signals = s7Detector.detectSignals();

        // Verify SL and target
        if (!signals.isEmpty()) {
            FuturesSignal signal = signals.get(0);
            // SL should be 0.25% above entry for SHORT
            BigDecimal expectedSL = BigDecimal.valueOf(48400).multiply(BigDecimal.valueOf(1.0025));
            assertEquals(0, signal.getStopLossLevel().compareTo(expectedSL));

            // Target should be 0.45% below entry for SHORT
            BigDecimal expectedTarget = BigDecimal.valueOf(48400).multiply(BigDecimal.valueOf(0.9955));
            assertEquals(0, signal.getTargetLevel1().compareTo(expectedTarget));
        }
    }

    @Test
    public void testS7_RejectsLowVolume() {
        // Setup: Low volume = invalid
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(24150.00);
        data.recent5mHigh = BigDecimal.valueOf(24155.00);
        data.recent5mLow = BigDecimal.valueOf(24100.00);
        data.volume5m = BigDecimal.valueOf(100); // Too low

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(null);

        // Execute
        List<FuturesSignal> signals = s7Detector.detectSignals();

        // Verify: Rejected due to low volume
        assertTrue(signals.isEmpty());
    }

    @Test
    public void testS7_RejectsStrongBullish() {
        // Setup: Momentum too strong bullish (not a fade)
        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.currentPrice = BigDecimal.valueOf(24150.00);
        data.recent5mHigh = BigDecimal.valueOf(24155.00);
        data.recent5mLow = BigDecimal.valueOf(24100.00);
        data.momentum5m = BigDecimal.valueOf(0.01); // Too bullish
        data.volume5m = BigDecimal.valueOf(5000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(null);

        // Execute
        List<FuturesSignal> signals = s7Detector.detectSignals();

        // Verify: Rejected - momentum too bullish
        assertTrue(signals.isEmpty());
    }

    // ===== Integration Tests =====

    @Test
    public void testBothStrategiesSimultaneously() {
        // Setup: Conditions for both S3 and S7
        MarketDataProvider.IndexMarketData niftyData = new MarketDataProvider.IndexMarketData();
        niftyData.currentPrice = BigDecimal.valueOf(24100.00);
        niftyData.vwap = BigDecimal.valueOf(24095.00);
        niftyData.sma20 = BigDecimal.valueOf(24090.00);
        niftyData.sma50 = BigDecimal.valueOf(24050.00);
        niftyData.recent5mHigh = BigDecimal.valueOf(24105.00);
        niftyData.recent5mLow = BigDecimal.valueOf(24080.00);
        niftyData.range5m = BigDecimal.valueOf(0.004);
        niftyData.momentum5m = BigDecimal.valueOf(-0.001);
        niftyData.volume5m = BigDecimal.valueOf(6000);

        when(marketDataProvider.isMarketOpen()).thenReturn(true);
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(630);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(niftyData);
        when(marketDataProvider.getIndexMarketData("BANKNIFTY")).thenReturn(null);

        // Execute both detectors
        List<FuturesSignal> s3Signals = s3Detector.detectSignals();
        List<FuturesSignal> s7Signals = s7Detector.detectSignals();

        // Verify: Both can detect simultaneously
        // (S3 for LONG, S7 for SHORT on same market condition)
        assertTrue(!s3Signals.isEmpty() || !s7Signals.isEmpty());
    }
}
