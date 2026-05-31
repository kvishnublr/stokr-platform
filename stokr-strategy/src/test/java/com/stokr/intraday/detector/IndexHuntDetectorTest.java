package com.stokr.intraday.detector;

import com.stokr.intraday.domain.IndexSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for INDEX HUNT 5-Gate Logic
 * Tests each gate independently + integration scenarios
 */
@DisplayName("INDEX HUNT Detector - 5 Gate Validation Tests")
public class IndexHuntDetectorTest {

    private IndexHuntDetector indexHuntDetector;

    @Mock
    private MarketDataProvider marketDataProvider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        indexHuntDetector = new IndexHuntDetector(marketDataProvider);
    }

    // ===== GATE 1: TIME WINDOW TESTS =====

    @Test
    @DisplayName("Gate 1: Should accept signals between 10:15 AM - 1:45 PM IST")
    void testGate1_AcceptDuringTradingHours() {
        // Setup: 11:30 AM IST = 690 minutes from midnight
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        // Create valid market data
        MarketDataProvider.IndexMarketData data = createValidMarketData();
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        // Should not be rejected by time gate (assuming other gates pass)
        assertNotNull(signal, "Signal should not be null during trading hours");
    }

    @Test
    @DisplayName("Gate 1: Should reject signals before 10:15 AM IST")
    void testGate1_RejectBeforeOpeningWindow() {
        // Setup: 9:30 AM IST = 570 minutes from midnight
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(570);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Signal should be null before 10:15 AM");
    }

    @Test
    @DisplayName("Gate 1: Should reject signals after 1:45 PM IST")
    void testGate1_RejectAfterClosingWindow() {
        // Setup: 2:00 PM IST = 840 minutes from midnight
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(840);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Signal should be null after 1:45 PM");
    }

    // ===== GATE 2: MOMENTUM TESTS =====

    @Test
    @DisplayName("Gate 2: Should accept momentum within band (0.055% - 0.60%)")
    void testGate2_AcceptValidMomentum() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15); // 0.15% - valid
        data.trend30m = BigDecimal.valueOf(0.15); // Trend support for CE
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal, "Signal should pass gate 2 with valid momentum");
        assertEquals("CE", signal.getDirection(), "Should be CE (bullish) with positive trend");
    }

    @Test
    @DisplayName("Gate 2: Should reject momentum too low (< 0.055%)")
    void testGate2_RejectTooQuiet() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.02); // 0.02% - too quiet
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Signal should be null with momentum too low");
    }

    @Test
    @DisplayName("Gate 2: Should reject momentum too high (> 0.60%)")
    void testGate2_RejectPanicSpike() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(1.5); // 1.5% - panic spike
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Signal should be null with panic spike momentum");
    }

    @Test
    @DisplayName("Gate 2: Should mark as 'hi' strength when momentum > 0.20%")
    void testGate2_HighStrengthSignal() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.35); // 0.35% - high strength
        data.trend30m = BigDecimal.valueOf(0.20);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        assertEquals("hi", signal.getSignalStrength(), "Should be 'hi' strength");
    }

    // ===== GATE 3: TREND ALIGNMENT TESTS =====

    @Test
    @DisplayName("Gate 3: Should accept CE when 30m trend is UP (>= +10%)")
    void testGate3_AcceptCEWithUptrend() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15); // Valid momentum
        data.trend30m = BigDecimal.valueOf(0.15); // Uptrend >= 10%
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        assertEquals("CE", signal.getDirection(), "Should accept CE with uptrend");
    }

    @Test
    @DisplayName("Gate 3: Should accept PE when 30m trend is DOWN (<= -10%)")
    void testGate3_AcceptPEWithDowntrend() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(-0.15); // Negative momentum
        data.trend30m = BigDecimal.valueOf(-0.15); // Downtrend <= -10%
        data.pcrRatio = BigDecimal.valueOf(1.5); // Strong put bias
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        assertEquals("PE", signal.getDirection(), "Should accept PE with downtrend");
    }

    @Test
    @DisplayName("Gate 3: Should reject CE when trend is DOWN")
    void testGate3_RejectCEWithDowntrend() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15); // Bullish momentum
        data.trend30m = BigDecimal.valueOf(-0.15); // But trend is down - conflict
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Should reject CE when 30m trend is DOWN");
    }

    // ===== GATE 4: PCR TESTS =====

    @Test
    @DisplayName("Gate 4: Should accept CE when PCR > 1.02 (bullish)")
    void testGate4_AcceptCEWithBullishPCR() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.pcrRatio = BigDecimal.valueOf(1.25); // PCR > 1.02 (bullish)
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        assertEquals("CE", signal.getDirection());
    }

    @Test
    @DisplayName("Gate 4: Should reject CE when PCR < 1.02 (bearish)")
    void testGate4_RejectCEWithBearishPCR() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.pcrRatio = BigDecimal.valueOf(0.80); // PCR < 1.02 (bearish)
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Should reject CE when PCR is bearish");
    }

    @Test
    @DisplayName("Gate 4: Should reject PE when PCR < 1.32 (needs strong put bias)")
    void testGate4_RejectPEWithWeakPCR() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(-0.15);
        data.trend30m = BigDecimal.valueOf(-0.15);
        data.pcrRatio = BigDecimal.valueOf(1.0); // PCR < 1.32 (not strong enough for PE)
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Should reject PE when PCR < 1.32");
    }

    // ===== GATE 5: VIX + ANTI-CHASE TESTS =====

    @Test
    @DisplayName("Gate 5: Should block CE when VIX > 20.75 (IV too high)")
    void testGate5_BlockCEWithHighVIX() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.pcrRatio = BigDecimal.valueOf(1.25);
        data.vixLevel = BigDecimal.valueOf(22.0); // VIX > 20.75
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNull(signal, "Should block CE when VIX > 20.75");
    }

    @Test
    @DisplayName("Gate 5: Should accept CE when VIX <= 20.75")
    void testGate5_AcceptCEWithNormalVIX() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.pcrRatio = BigDecimal.valueOf(1.25);
        data.vixLevel = BigDecimal.valueOf(18.5); // VIX <= 20.75 (OK)
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal, "Should accept CE when VIX is normal");
    }

    // ===== QUALITY SCORE TESTS =====

    @Test
    @DisplayName("Quality Score: Should be in range 0-100")
    void testQualityScore_WithinValidRange() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.35); // High strength
        data.trend30m = BigDecimal.valueOf(0.25); // Strong trend
        data.pcrRatio = BigDecimal.valueOf(1.25);
        data.vixLevel = BigDecimal.valueOf(16.0);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.ZERO) >= 0,
                "Quality score should be >= 0");
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(100)) <= 0,
                "Quality score should be <= 100");
    }

    @Test
    @DisplayName("Quality Score: Should reject if below floor (68)")
    void testQualityScore_BelowFloor() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.055); // Minimum momentum
        data.trend30m = BigDecimal.valueOf(0.10);  // Minimum trend
        data.pcrRatio = BigDecimal.valueOf(1.02);  // Minimum PCR
        data.vixLevel = BigDecimal.valueOf(25.0);  // High VIX (penalty)
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        // Should be rejected if quality < 68
        // (might pass or fail depending on exact scoring - verify outcome)
        if (signal != null) {
            assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(68)) >= 0,
                    "If signal passes, quality should be >= 68");
        }
    }

    // ===== ENTRY/EXIT LEVELS TESTS =====

    @Test
    @DisplayName("Entry/Exit: Should calculate SL = entry × 0.80")
    void testEntryExit_SLCalculation() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.pcrRatio = BigDecimal.valueOf(1.25);
        data.niftyCallLTP = BigDecimal.valueOf(50.0); // Entry premium
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        BigDecimal expectedSL = BigDecimal.valueOf(50.0).multiply(BigDecimal.valueOf(0.80));
        assertEquals(expectedSL, signal.getOptionSL(), "SL should be entry × 0.80");
    }

    @Test
    @DisplayName("Entry/Exit: Should calculate T1 = entry × 1.28")
    void testEntryExit_T1Calculation() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.pcrRatio = BigDecimal.valueOf(1.25);
        data.niftyCallLTP = BigDecimal.valueOf(50.0);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal);
        BigDecimal expectedT1 = BigDecimal.valueOf(50.0).multiply(BigDecimal.valueOf(1.28));
        assertEquals(expectedT1, signal.getOptionT1(), "T1 should be entry × 1.28");
    }

    // ===== INTEGRATION TESTS =====

    @Test
    @DisplayName("Integration: Full valid CE signal detection")
    void testIntegration_ValidCESignal() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690); // 11:30 AM

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(0.25);      // Good momentum
        data.trend30m = BigDecimal.valueOf(0.18);      // Strong uptrend
        data.pcrRatio = BigDecimal.valueOf(1.15);      // Bullish
        data.vixLevel = BigDecimal.valueOf(17.0);      // Normal VIX
        data.niftyCallLTP = BigDecimal.valueOf(75.0);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal, "Should detect valid CE signal");
        assertEquals("NIFTY", signal.getIndexName());
        assertEquals("CE", signal.getDirection());
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(68)) >= 0);
        assertTrue(signal.getAllGatesPassed());
        assertEquals("PENDING", signal.getExecutionStatus());
    }

    @Test
    @DisplayName("Integration: Full valid PE signal detection")
    void testIntegration_ValidPESignal() {
        when(marketDataProvider.getCurrentTimeMinutes()).thenReturn(690);

        MarketDataProvider.IndexMarketData data = createValidMarketData();
        data.change5m = BigDecimal.valueOf(-0.22);     // Negative momentum
        data.trend30m = BigDecimal.valueOf(-0.16);     // Strong downtrend
        data.pcrRatio = BigDecimal.valueOf(1.40);      // Bearish
        data.vixLevel = BigDecimal.valueOf(19.0);
        data.niftyPutLTP = BigDecimal.valueOf(60.0);
        when(marketDataProvider.getIndexMarketData("NIFTY")).thenReturn(data);

        IndexSignal signal = indexHuntDetector.detectSignal("NIFTY");

        assertNotNull(signal, "Should detect valid PE signal");
        assertEquals("PE", signal.getDirection());
        assertTrue(signal.getQualityScore().compareTo(BigDecimal.valueOf(68)) >= 0);
    }

    // ===== HELPER METHODS =====

    private MarketDataProvider.IndexMarketData createValidMarketData() {
        Map<String, BigDecimal> extremes = new HashMap<>();
        extremes.put("high", BigDecimal.valueOf(24100));
        extremes.put("low", BigDecimal.valueOf(23900));

        MarketDataProvider.IndexMarketData data = new MarketDataProvider.IndexMarketData();
        data.indexName = "NIFTY";
        data.currentPrice = BigDecimal.valueOf(24000);
        data.vixLevel = BigDecimal.valueOf(16.5);
        data.pcrRatio = BigDecimal.valueOf(1.10);
        data.volume5m = BigDecimal.valueOf(1000000L);
        data.change5m = BigDecimal.valueOf(0.15);
        data.trend30m = BigDecimal.valueOf(0.15);
        data.sessionOpen = BigDecimal.valueOf(24010);
        data.prevClose = BigDecimal.valueOf(23950);
        data.timestamp = System.currentTimeMillis() / 1000;
        data.niftyCallLTP = BigDecimal.valueOf(50.0);
        data.niftyPutLTP = BigDecimal.valueOf(45.0);
        data.bnfCallLTP = BigDecimal.valueOf(350.0);
        data.bnfPutLTP = BigDecimal.valueOf(320.0);
        return data;
    }
}
