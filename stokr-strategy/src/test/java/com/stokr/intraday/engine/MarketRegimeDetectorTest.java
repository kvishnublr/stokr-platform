package com.stokr.intraday.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MarketRegimeDetectorTest {

    private MarketRegimeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new MarketRegimeDetector();
    }

    @Test
    void testDetectTrendingUpRegime() {
        // Strong uptrend: +5% change, +2.0 momentum (larger values for trend score calculation)
        // trendScore = (5%/5 + 2.0/2) / 2 = (0.01 + 1.0) / 2 = 0.505 which exceeds 0.3 threshold
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.05); // +5%
        BigDecimal momentum = BigDecimal.valueOf(2.0); // Strong up momentum
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.012);
        BigDecimal volumeRatio = BigDecimal.valueOf(1.2);

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot);
        assertEquals(MarketRegimeDetector.MarketRegime.TRENDING_UP, snapshot.regime);
    }

    @Test
    void testDetectTrendingDownRegime() {
        // Strong downtrend: -5% change, -2.0 momentum (larger values for trend score calculation)
        // trendScore = (-5%/5 + -2.0/2) / 2 = (-0.01 - 1.0) / 2 = -0.505 which exceeds -0.3 threshold
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(-0.05); // -5%
        BigDecimal momentum = BigDecimal.valueOf(-2.0); // Strong down
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.012);
        BigDecimal volumeRatio = BigDecimal.valueOf(1.2);

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot);
        assertEquals(MarketRegimeDetector.MarketRegime.TRENDING_DOWN, snapshot.regime);
    }

    @Test
    void testDetectChoppyRegime() {
        // Choppy: No clear trend but high volume, no strong momentum
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.0); // Flat
        BigDecimal momentum = BigDecimal.valueOf(0.1); // Mild
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.015);
        BigDecimal volumeRatio = BigDecimal.valueOf(1.3); // High volume

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot);
        assertEquals(MarketRegimeDetector.MarketRegime.CHOPPY, snapshot.regime);
    }

    @Test
    void testDetectVolatileRegime() {
        // Volatile: High volatility, no clear trend
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.0); // Flat
        BigDecimal momentum = BigDecimal.valueOf(0.05); // Weak
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.030); // High volatility (>0.025)
        BigDecimal volumeRatio = BigDecimal.valueOf(1.0);

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot);
        assertEquals(MarketRegimeDetector.MarketRegime.VOLATILE, snapshot.regime);
    }

    @Test
    void testDetectQuietRegime() {
        // Quiet: Low volatility, low volume
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.005); // Flat
        BigDecimal momentum = BigDecimal.valueOf(0.2);
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.008); // Low volatility (<0.010)
        BigDecimal volumeRatio = BigDecimal.valueOf(0.7); // Low volume (<0.8)

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot);
        assertEquals(MarketRegimeDetector.MarketRegime.QUIET, snapshot.regime);
    }

    @Test
    void testRegimeAdjustmentGapFillTrendingUp() {
        // Gap fill gets +15% in trending up
        BigDecimal adjustment = detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.TRENDING_UP, "gap_fill"
        );
        assertEquals(BigDecimal.valueOf(15), adjustment);
    }

    @Test
    void testRegimeAdjustmentGapFillTrendingDown() {
        // Gap fill gets -15% in trending down
        BigDecimal adjustment = detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.TRENDING_DOWN, "gap_fill"
        );
        assertEquals(BigDecimal.valueOf(-15), adjustment);
    }

    @Test
    void testRegimeAdjustmentVwapBounceQuiet() {
        // VWAP bounce gets +10% in quiet market
        BigDecimal adjustment = detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.QUIET, "vwap_bounce"
        );
        assertEquals(BigDecimal.valueOf(10), adjustment);
    }

    @Test
    void testRegimeAdjustmentSectorLaggardTrendingUp() {
        // Sector laggard gets -5% in trending up (counter-trend opportunity)
        BigDecimal adjustment = detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.TRENDING_UP, "sector_laggard"
        );
        assertEquals(BigDecimal.valueOf(-5), adjustment);
    }

    @Test
    void testRegimeAdjustmentEarlyBreakoutTrendingUp() {
        // Early breakout gets +10% in trending up
        BigDecimal adjustment = detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.TRENDING_UP, "early_breakout"
        );
        assertEquals(BigDecimal.valueOf(10), adjustment);
    }

    @Test
    void testRegimeAdjustmentChoppyPenalty() {
        // All setups get -10% in choppy market
        assertEquals(BigDecimal.valueOf(-10), detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.CHOPPY, "gap_fill"));
        assertEquals(BigDecimal.valueOf(-10), detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.CHOPPY, "vwap_bounce"));
    }

    @Test
    void testRegimeAdjustmentVolatilePenalty() {
        // All setups get -5% in volatile market
        assertEquals(BigDecimal.valueOf(-5), detector.getRegimeAdjustment(
                MarketRegimeDetector.MarketRegime.VOLATILE, "gap_fill"));
    }

    @Test
    void testTrendScoreCalculation() {
        // Trend score should be between -1 and +1
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.05);
        BigDecimal momentum = BigDecimal.valueOf(0.5);
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.015);
        BigDecimal volumeRatio = BigDecimal.valueOf(1.0);

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot.trendScore);
        assertTrue(snapshot.trendScore.compareTo(BigDecimal.valueOf(-1)) >= 0);
        assertTrue(snapshot.trendScore.compareTo(BigDecimal.valueOf(1)) <= 0);
    }

    @Test
    void testRegimeConsistencyWithInputs() {
        // Verify regime is consistent with inputs - use QUIET market parameters
        // Low volatility + low volume = QUIET
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.005); // Minimal change
        BigDecimal momentum = BigDecimal.valueOf(0.15); // Weak
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.008); // Low
        BigDecimal volumeRatio = BigDecimal.valueOf(0.75); // Low volume

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        // With low volatility and low volume should be QUIET market
        assertEquals(MarketRegimeDetector.MarketRegime.QUIET, snapshot.regime);
    }

    @Test
    void testRegimeSnapshotPopulation() {
        // Verify all snapshot fields are populated
        BigDecimal nifty50Price = BigDecimal.valueOf(20000.00);
        BigDecimal nifty50Change = BigDecimal.valueOf(0.01);
        BigDecimal momentum = BigDecimal.valueOf(0.5);
        BigDecimal volatilityRatio = BigDecimal.valueOf(0.015);
        BigDecimal volumeRatio = BigDecimal.valueOf(1.1);

        MarketRegimeDetector.RegimeSnapshot snapshot = detector.detectRegime(
                nifty50Price, nifty50Change, momentum, volatilityRatio, volumeRatio
        );

        assertNotNull(snapshot.regime);
        assertNotNull(snapshot.nifty50Price);
        assertNotNull(snapshot.nifty50Change);
        assertNotNull(snapshot.trendScore);
        assertNotNull(snapshot.momentum);
        assertNotNull(snapshot.volatilityRatio);
        assertNotNull(snapshot.volumeRatio);
        assertTrue(snapshot.timestamp > 0);
    }
}
