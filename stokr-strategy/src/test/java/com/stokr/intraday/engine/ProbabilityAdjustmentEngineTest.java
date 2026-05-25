package com.stokr.intraday.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ProbabilityAdjustmentEngineTest {

    private ProbabilityAdjustmentEngine engine;
    private MarketRegimeDetector regimeDetector;

    @BeforeEach
    void setUp() {
        regimeDetector = new MarketRegimeDetector();
        engine = new ProbabilityAdjustmentEngine(regimeDetector);
    }

    @Test
    void testGapFillBaselineProbability() {
        // Gap fill in trending up market should get +15% boost
        BigDecimal baseProbability = BigDecimal.valueOf(0.70); // 70% base
        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.TRENDING_UP;
        String setupType = "gap_fill";
        BigDecimal sectorMomentum = BigDecimal.ZERO;
        BigDecimal recentPerformance = null;
        Integer hourOfDay = 9;

        BigDecimal adjusted = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, recentPerformance, hourOfDay
        );

        // Should be higher than baseline due to regime (+15%) and time (+10%)
        assertTrue(adjusted.compareTo(baseProbability) > 0, "Adjusted probability should be higher in TRENDING_UP with good time");
        // Should not exceed 0.95 cap
        assertTrue(adjusted.compareTo(BigDecimal.valueOf(0.95)) <= 0, "Probability should not exceed 0.95");
    }

    @Test
    void testVwapBounceTimeOfDayAdjustment() {
        // VWAP bounce works best 10-11, should be boosted
        BigDecimal baseProbability = BigDecimal.valueOf(0.65);
        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.CHOPPY;
        String setupType = "vwap_bounce";
        BigDecimal sectorMomentum = BigDecimal.ZERO;
        BigDecimal recentPerformance = null;

        // At hour 11 (good time for VWAP)
        BigDecimal adjusted11 = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, recentPerformance, 11
        );

        // At hour 14 (late, worse for VWAP)
        BigDecimal adjusted14 = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, recentPerformance, 14
        );

        assertTrue(adjusted11.compareTo(adjusted14) > 0, "VWAP should be better at hour 11 than 14");
    }

    @Test
    void testEarlyBreakoutPenaltyAfterHour10() {
        // Early breakout is -100% penalty after hour 10 (not applicable)
        BigDecimal baseProbability = BigDecimal.valueOf(0.75);
        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.TRENDING_UP;
        String setupType = "early_breakout";
        BigDecimal sectorMomentum = BigDecimal.ZERO;
        BigDecimal recentPerformance = null;

        // At hour 9 (valid for early breakout)
        BigDecimal adjusted9 = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, recentPerformance, 9
        );

        // At hour 11 (not valid - should be heavily penalized)
        BigDecimal adjusted11 = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, recentPerformance, 11
        );

        assertTrue(adjusted9.compareTo(adjusted11) > 0, "Early breakout should be much worse at hour 11");
        assertTrue(adjusted11.compareTo(BigDecimal.valueOf(0.30)) <= 0, "Should be near minimum 0.30 cap");
    }

    @Test
    void testSectorMomentumBoost() {
        // Positive sector momentum should boost probability
        BigDecimal baseProbability = BigDecimal.valueOf(0.65);
        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.TRENDING_UP;
        String setupType = "sector_laggard";
        BigDecimal positiveMomentum = BigDecimal.valueOf(0.05); // +5%
        BigDecimal negativeMomentum = BigDecimal.valueOf(-0.05); // -5%
        BigDecimal recentPerformance = null;
        Integer hourOfDay = 12;

        BigDecimal adjustedPositive = engine.adjustProbability(
                baseProbability, regime, setupType, positiveMomentum, recentPerformance, hourOfDay
        );

        BigDecimal adjustedNegative = engine.adjustProbability(
                baseProbability, regime, setupType, negativeMomentum, recentPerformance, hourOfDay
        );

        assertTrue(adjustedPositive.compareTo(adjustedNegative) > 0,
                "Positive sector momentum should boost probability vs negative");
    }

    @Test
    void testRecentPerformanceDivergence() {
        // If recent performance exceeds base, should get boost
        BigDecimal baseProbability = BigDecimal.valueOf(0.65);
        BigDecimal goodRecentPerformance = BigDecimal.valueOf(0.75); // Better than base
        BigDecimal poorRecentPerformance = BigDecimal.valueOf(0.55); // Worse than base

        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.TRENDING_UP;
        String setupType = "gap_fill";
        BigDecimal sectorMomentum = BigDecimal.ZERO;
        Integer hourOfDay = 9;

        BigDecimal adjustedGood = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, goodRecentPerformance, hourOfDay
        );

        BigDecimal adjustedPoor = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, poorRecentPerformance, hourOfDay
        );

        assertTrue(adjustedGood.compareTo(adjustedPoor) > 0,
                "Good recent performance should boost probability");
    }

    @Test
    void testProbabilityBounds() {
        // Probability should always be between 0.30 and 0.95
        BigDecimal veryLow = BigDecimal.valueOf(0.10);
        BigDecimal veryHigh = BigDecimal.valueOf(0.99);

        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.TRENDING_DOWN;
        String setupType = "early_breakout";
        BigDecimal sectorMomentum = BigDecimal.ZERO;
        Integer hourOfDay = 15;

        BigDecimal adjustedLow = engine.adjustProbability(
                veryLow, regime, setupType, sectorMomentum, null, hourOfDay
        );

        BigDecimal adjustedHigh = engine.adjustProbability(
                veryHigh, regime, setupType, sectorMomentum, null, hourOfDay
        );

        assertTrue(adjustedLow.compareTo(BigDecimal.valueOf(0.30)) >= 0, "Minimum 0.30");
        assertTrue(adjustedHigh.compareTo(BigDecimal.valueOf(0.95)) <= 0, "Maximum 0.95");
    }

    @Test
    void testExpectedValueCalculation() {
        // E[V] = prob * win - (1 - prob) * loss
        // Example: 70% win rate, +2% avg win, -1% avg loss
        // E[V] = 0.70 * 2% - 0.30 * 1% = 1.4% - 0.3% = 1.1%
        BigDecimal probability = BigDecimal.valueOf(0.70);
        BigDecimal avgWinPercent = BigDecimal.valueOf(0.02);
        BigDecimal avgLossPercent = BigDecimal.valueOf(-0.01);

        BigDecimal expectedValue = engine.calculateExpectedValue(probability, avgWinPercent, avgLossPercent);

        // Expected: 0.70 * 0.02 - 0.30 * 0.01 = 0.014 - 0.003 = 0.011
        assertTrue(expectedValue.compareTo(BigDecimal.valueOf(0.010)) > 0, "EV should be positive");
        assertTrue(expectedValue.compareTo(BigDecimal.valueOf(0.015)) < 0, "EV should be around 1.1%");
    }

    @Test
    void testConfidenceLevelAssignment() {
        // Sample size determines confidence
        assertEquals("HIGH", engine.getConfidenceLevel(150), ">=100 should be HIGH");
        assertEquals("MEDIUM", engine.getConfidenceLevel(75), "50-99 should be MEDIUM");
        assertEquals("LOW", engine.getConfidenceLevel(30), "<50 should be LOW");
        assertEquals("LOW", engine.getConfidenceLevel(0), "0 should be LOW");
    }

    @Test
    void testMultiFactorAccumulation() {
        // Verify multiple adjustments stack correctly
        // Base: 0.65
        // Regime adjustment (TRENDING_UP, gap_fill): +15%
        // Time adjustment (hour 9, gap_fill): +10%
        // Sector momentum (0.03): +1.5%
        // Recent performance (0.70 vs 0.65 base): +5%
        BigDecimal baseProbability = BigDecimal.valueOf(0.65);
        MarketRegimeDetector.MarketRegime regime = MarketRegimeDetector.MarketRegime.TRENDING_UP;
        String setupType = "gap_fill";
        BigDecimal sectorMomentum = BigDecimal.valueOf(0.03);
        BigDecimal recentPerformance = BigDecimal.valueOf(0.70);
        Integer hourOfDay = 9;

        BigDecimal adjusted = engine.adjustProbability(
                baseProbability, regime, setupType, sectorMomentum, recentPerformance, hourOfDay
        );

        // Should be significantly higher due to multiple positive factors
        assertTrue(adjusted.compareTo(baseProbability) > 0, "Multiple positive adjustments should boost probability");
        assertTrue(adjusted.compareTo(BigDecimal.valueOf(0.85)) > 0, "Should be substantially boosted");
        assertTrue(adjusted.compareTo(BigDecimal.valueOf(0.95)) <= 0, "Should respect 0.95 cap");
    }
}
