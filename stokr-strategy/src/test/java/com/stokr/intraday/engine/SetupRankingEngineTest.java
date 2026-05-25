package com.stokr.intraday.engine;

import com.stokr.intraday.domain.CurrentSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetupRankingEngineTest {

    private SetupRankingEngine rankingEngine;
    private ProbabilityAdjustmentEngine probabilityEngine;
    private MarketRegimeDetector regimeDetector;

    @BeforeEach
    void setUp() {
        regimeDetector = new MarketRegimeDetector();
        probabilityEngine = new ProbabilityAdjustmentEngine(regimeDetector);
        rankingEngine = new SetupRankingEngine(probabilityEngine);
    }

    @Test
    void testQualityScoreCalculation() {
        // Create a high-quality setup
        CurrentSetup setup = new CurrentSetup();
        setup.setStockId("INFY");
        setup.setSetupType("gap_fill");
        setup.setEntryPrice(BigDecimal.valueOf(1500.00));
        setup.setTargetPrice(BigDecimal.valueOf(1600.00));
        setup.setStopLoss(BigDecimal.valueOf(1450.00));
        setup.setRiskAmount(BigDecimal.valueOf(50.00));
        setup.setRewardAmount(BigDecimal.valueOf(100.00));
        setup.setRiskRewardRatio(BigDecimal.valueOf(2.0));
        setup.setConfidenceLevel("HIGH");

        BigDecimal probability = BigDecimal.valueOf(0.80); // 80% win rate
        BigDecimal avgWin = BigDecimal.valueOf(0.025); // 2.5%
        BigDecimal avgLoss = BigDecimal.valueOf(-0.01); // -1%
        Integer sampleSize = 150; // HIGH confidence

        BigDecimal score = rankingEngine.calculateQualityScore(
                setup, probability, avgWin, avgLoss, sampleSize
        );

        assertNotNull(score);
        assertTrue(score.compareTo(BigDecimal.ZERO) > 0, "Score should be positive");
        assertTrue(score.compareTo(BigDecimal.valueOf(100)) <= 0, "Score should be <= 100");
        // Score breakdown: Prob(30.77) + RR(10) + Conf(15) + EV(7) = ~62.77
        assertTrue(score.compareTo(BigDecimal.valueOf(60)) > 0, "High quality setup should score > 60");
    }

    @Test
    void testQualityScoreProbabilityComponent() {
        // Test that probability is weighted correctly (40%)
        // High probability (0.90) should score high
        CurrentSetup setup = new CurrentSetup();
        setup.setRiskRewardRatio(BigDecimal.valueOf(1.5));
        setup.setConfidenceLevel("MEDIUM");

        BigDecimal highProbScore = rankingEngine.calculateQualityScore(
                setup, BigDecimal.valueOf(0.90), BigDecimal.valueOf(0.02), BigDecimal.valueOf(-0.01), 75
        );

        // Low probability (0.40) should score low
        BigDecimal lowProbScore = rankingEngine.calculateQualityScore(
                setup, BigDecimal.valueOf(0.40), BigDecimal.valueOf(0.02), BigDecimal.valueOf(-0.01), 75
        );

        assertTrue(highProbScore.compareTo(lowProbScore) > 0, "Higher probability should yield higher score");
    }

    @Test
    void testQualityScoreRiskRewardComponent() {
        // Test that risk/reward ratio is weighted correctly (30%)
        CurrentSetup setupGoodRR = new CurrentSetup();
        setupGoodRR.setRiskRewardRatio(BigDecimal.valueOf(3.0));
        setupGoodRR.setConfidenceLevel("HIGH");

        CurrentSetup setupPoorRR = new CurrentSetup();
        setupPoorRR.setRiskRewardRatio(BigDecimal.valueOf(1.5));
        setupPoorRR.setConfidenceLevel("HIGH");

        BigDecimal scoreGoodRR = rankingEngine.calculateQualityScore(
                setupGoodRR, BigDecimal.valueOf(0.70), BigDecimal.valueOf(0.02), BigDecimal.valueOf(-0.01), 100
        );

        BigDecimal scorePoorRR = rankingEngine.calculateQualityScore(
                setupPoorRR, BigDecimal.valueOf(0.70), BigDecimal.valueOf(0.02), BigDecimal.valueOf(-0.01), 100
        );

        assertTrue(scoreGoodRR.compareTo(scorePoorRR) > 0, "Better R:R should yield higher score");
    }

    @Test
    void testQualityScoreConfidenceComponent() {
        // Test that confidence level impacts score (15%)
        CurrentSetup setup = new CurrentSetup();
        setup.setRiskRewardRatio(BigDecimal.valueOf(2.0));

        BigDecimal scoreHigh = rankingEngine.calculateQualityScore(
                setup, BigDecimal.valueOf(0.70), BigDecimal.valueOf(0.02), BigDecimal.valueOf(-0.01), 150
        );

        BigDecimal scoreLow = rankingEngine.calculateQualityScore(
                setup, BigDecimal.valueOf(0.70), BigDecimal.valueOf(0.02), BigDecimal.valueOf(-0.01), 25
        );

        assertTrue(scoreHigh.compareTo(scoreLow) > 0, "HIGH confidence should score higher than LOW");
    }

    @Test
    void testRankingSetups() {
        // Create multiple setups with different scores
        List<CurrentSetup> setups = new ArrayList<>();

        // High quality setup
        CurrentSetup high = new CurrentSetup();
        high.setStockId("INFY");
        high.setQualityScore(BigDecimal.valueOf(85.0));
        setups.add(high);

        // Medium quality setup
        CurrentSetup medium = new CurrentSetup();
        medium.setStockId("TCS");
        medium.setQualityScore(BigDecimal.valueOf(65.0));
        setups.add(medium);

        // Low quality setup
        CurrentSetup low = new CurrentSetup();
        low.setStockId("WIPRO");
        low.setQualityScore(BigDecimal.valueOf(45.0));
        setups.add(low);

        List<CurrentSetup> ranked = rankingEngine.rankSetups(setups);

        assertEquals(3, ranked.size());
        assertEquals("INFY", ranked.get(0).getStockId());
        assertEquals("TCS", ranked.get(1).getStockId());
        assertEquals("WIPRO", ranked.get(2).getStockId());
    }

    @Test
    void testGetTopSetups() {
        List<CurrentSetup> setups = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            CurrentSetup setup = new CurrentSetup();
            setup.setStockId("STOCK" + i);
            setup.setQualityScore(BigDecimal.valueOf(100 - i * 5)); // 95, 90, 85, ...
            setups.add(setup);
        }

        List<CurrentSetup> top3 = rankingEngine.getTopSetups(setups, 3);

        assertEquals(3, top3.size());
        assertEquals("STOCK1", top3.get(0).getStockId()); // 95
        assertEquals("STOCK2", top3.get(1).getStockId()); // 90
        assertEquals("STOCK3", top3.get(2).getStockId()); // 85
    }

    @Test
    void testFilterByMinimumQuality() {
        List<CurrentSetup> setups = new ArrayList<>();

        CurrentSetup high = new CurrentSetup();
        high.setStockId("GOOD");
        high.setQualityScore(BigDecimal.valueOf(75.0));
        setups.add(high);

        CurrentSetup low = new CurrentSetup();
        low.setStockId("BAD");
        low.setQualityScore(BigDecimal.valueOf(55.0));
        setups.add(low);

        List<CurrentSetup> filtered = rankingEngine.filterByMinimumQuality(setups, BigDecimal.valueOf(70.0));

        assertEquals(1, filtered.size());
        assertEquals("GOOD", filtered.get(0).getStockId());
    }

    @Test
    void testFilterByType() {
        List<CurrentSetup> setups = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            CurrentSetup gapFill = new CurrentSetup();
            gapFill.setStockId("GAP" + i);
            gapFill.setSetupType("gap_fill");
            gapFill.setQualityScore(BigDecimal.valueOf(80.0));
            setups.add(gapFill);

            CurrentSetup vwap = new CurrentSetup();
            vwap.setStockId("VWAP" + i);
            vwap.setSetupType("vwap_bounce");
            vwap.setQualityScore(BigDecimal.valueOf(70.0));
            setups.add(vwap);
        }

        List<CurrentSetup> gapFills = rankingEngine.filterByType(setups, "gap_fill");
        List<CurrentSetup> vwaps = rankingEngine.filterByType(setups, "vwap_bounce");

        assertEquals(3, gapFills.size());
        assertEquals(3, vwaps.size());
        assertTrue(gapFills.stream().allMatch(s -> s.getSetupType().equals("gap_fill")));
        assertTrue(vwaps.stream().allMatch(s -> s.getSetupType().equals("vwap_bounce")));
    }

    @Test
    void testFilterByConfidence() {
        List<CurrentSetup> setups = new ArrayList<>();

        CurrentSetup high = new CurrentSetup();
        high.setStockId("HIGH");
        high.setConfidenceLevel("HIGH");
        high.setQualityScore(BigDecimal.valueOf(80.0));
        setups.add(high);

        CurrentSetup medium = new CurrentSetup();
        medium.setStockId("MEDIUM");
        medium.setConfidenceLevel("MEDIUM");
        medium.setQualityScore(BigDecimal.valueOf(70.0));
        setups.add(medium);

        List<CurrentSetup> highConf = rankingEngine.filterByConfidence(setups, "HIGH");

        assertEquals(1, highConf.size());
        assertEquals("HIGH", highConf.get(0).getStockId());
    }

    @Test
    void testEmptySetupList() {
        List<CurrentSetup> empty = new ArrayList<>();
        List<CurrentSetup> ranked = rankingEngine.rankSetups(empty);

        assertNotNull(ranked);
        assertTrue(ranked.isEmpty());
    }

    @Test
    void testSetupWithNullScores() {
        List<CurrentSetup> setups = new ArrayList<>();

        CurrentSetup valid = new CurrentSetup();
        valid.setStockId("VALID");
        valid.setQualityScore(BigDecimal.valueOf(80.0));
        setups.add(valid);

        CurrentSetup invalid = new CurrentSetup();
        invalid.setStockId("INVALID");
        invalid.setQualityScore(null);
        setups.add(invalid);

        List<CurrentSetup> ranked = rankingEngine.rankSetups(setups);

        // Only valid setup should be returned
        assertEquals(1, ranked.size());
        assertEquals("VALID", ranked.get(0).getStockId());
    }

    @Test
    void testQualityScoreBounds() {
        // Quality score should always be 0-100
        CurrentSetup setup = new CurrentSetup();
        setup.setRiskRewardRatio(BigDecimal.valueOf(10.0)); // Very high R:R

        BigDecimal score = rankingEngine.calculateQualityScore(
                setup, BigDecimal.valueOf(0.95), BigDecimal.valueOf(0.10), BigDecimal.valueOf(-0.01), 200
        );

        assertTrue(score.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(score.compareTo(BigDecimal.valueOf(100)) <= 0);
    }
}
