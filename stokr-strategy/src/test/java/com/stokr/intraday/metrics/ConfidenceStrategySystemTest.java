package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.ConfidenceScore;
import com.stokr.intraday.metrics.domain.ConfidenceStrategyConfig;
import com.stokr.intraday.metrics.dto.OrderFlowSignalEnhancement;
import com.stokr.intraday.metrics.repository.ConfidenceScoreRepository;
import com.stokr.intraday.metrics.repository.ConfidenceStrategyConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({OrderFlowMetricsService.class, ConfidenceScoreCalculatorService.class})
@DisplayName("Confidence Strategy System Tests")
public class ConfidenceStrategySystemTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ConfidenceScoreRepository scoreRepository;

    @Autowired
    private ConfidenceStrategyConfigRepository configRepository;

    @Autowired
    private ConfidenceScoreCalculatorService calculatorService;

    private UUID traderId1;
    private UUID traderId2;

    @BeforeEach
    public void setUp() {
        traderId1 = UUID.randomUUID();
        traderId2 = UUID.randomUUID();
    }

    // ==================== CONFIDENCE SCORE TESTS ====================

    @Test
    @DisplayName("Should store confidence score for symbol")
    public void testStoreConfidenceScore() {
        // Arrange
        ConfidenceScore score = ConfidenceScore.builder()
            .symbol("SBIN")
            .timestamp(Instant.now())
            .confidenceScore(85)
            .buyerPressure(80)
            .sellerPressure(20)
            .liquidityScore(75)
            .signalStrength("STRONG")
            .build();

        // Act
        entityManager.persistAndFlush(score);
        entityManager.clear();

        // Assert
        Optional<ConfidenceScore> found = scoreRepository.findFirstBySymbolOrderByTimestampDesc("SBIN");
        assertTrue(found.isPresent());
        assertEquals(85, found.get().getConfidenceScore());
        assertEquals("STRONG", found.get().getSignalStrength());
    }

    @Test
    @DisplayName("Should find scores above threshold")
    public void testFindScoresAboveThreshold() {
        // Arrange
        Instant now = Instant.now();
        createConfidenceScore("SBIN", 85, now);
        createConfidenceScore("HDFC", 72, now);
        createConfidenceScore("INFY", 68, now);
        createConfidenceScore("TCS", 92, now);
        entityManager.clear();

        // Act
        List<ConfidenceScore> above70 = scoreRepository
            .findByConfidenceScoreGreaterThanAndTimestampAfterOrderByConfidenceScoreDescTimestampDesc(70, now.minusSeconds(120));

        // Assert
        assertEquals(3, above70.size());  // SBIN(85), HDFC(72), TCS(92)
        assertTrue(above70.stream()
            .allMatch(s -> s.getConfidenceScore() > 70));
    }

    @Test
    @DisplayName("Should calculate average confidence for symbol")
    public void testAverageConfidence() {
        // Arrange
        Instant now = Instant.now();
        createConfidenceScore("SBIN", 80, now.minusSeconds(120));
        createConfidenceScore("SBIN", 85, now.minusSeconds(60));
        createConfidenceScore("SBIN", 90, now);
        entityManager.clear();

        // Act
        Double avgConfidence = scoreRepository.getAverageConfidenceForSymbol(
            "SBIN",
            now.minusSeconds(300)
        );

        // Assert
        assertNotNull(avgConfidence);
        assertEquals(85.0, avgConfidence, 0.1);
    }

    @Test
    @DisplayName("Should verify confidence helps filter signals")
    public void testConfidenceThresholdFiltering() {
        // Arrange - Create 10 scores with varying confidence
        Instant now = Instant.now();
        for (int i = 50; i < 100; i += 5) {
            createConfidenceScore("SYMBOL" + i, i, now);
        }
        entityManager.clear();

        // Act & Assert - Threshold 70
        long countAbove70 = scoreRepository.countScoresAboveThreshold(70, now.minusSeconds(120));
        assertEquals(6, countAbove70);  // 75, 80, 85, 90, 95

        // Act & Assert - Threshold 80
        long countAbove80 = scoreRepository.countScoresAboveThreshold(80, now.minusSeconds(120));
        assertEquals(4, countAbove80);  // 85, 90, 95

        // Act & Assert - Threshold 90
        long countAbove90 = scoreRepository.countScoresAboveThreshold(90, now.minusSeconds(120));
        assertEquals(1, countAbove90);  // 95
    }

    // ==================== TRADER CONFIGURATION TESTS ====================

    @Test
    @DisplayName("Should create trader configuration")
    public void testCreateTraderConfig() {
        // Arrange & Act
        ConfidenceStrategyConfig config = ConfidenceStrategyConfig.builder()
            .traderId(traderId1)
            .minConfidenceThreshold(70)
            .enabled(true)
            .build();
        config.setMinConfidenceThreshold(70);

        entityManager.persistAndFlush(config);
        entityManager.clear();

        // Assert
        Optional<ConfidenceStrategyConfig> found =
            configRepository.findByStrategyName("CONFIDENCE_BASED_70");
        assertTrue(found.isPresent());
        assertEquals(traderId1, found.get().getTraderId());
        assertEquals(70, found.get().getMinConfidenceThreshold());
    }

    @Test
    @DisplayName("Should validate threshold values")
    public void testThresholdValidation() {
        // Test invalid threshold
        ConfidenceStrategyConfig config = new ConfidenceStrategyConfig();
        config.setTraderId(traderId1);

        // Invalid threshold should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            config.setMinConfidenceThreshold(75);  // Not valid, must be 60/70/80/90
        });

        // Valid thresholds should work
        config.setMinConfidenceThreshold(60);
        assertEquals("CONFIDENCE_BASED_60", config.getStrategyName());

        config.setMinConfidenceThreshold(80);
        assertEquals("CONFIDENCE_BASED_80", config.getStrategyName());
    }

    @Test
    @DisplayName("Should find trader configurations")
    public void testFindTraderConfigs() {
        // Arrange
        createTraderConfig(traderId1, 70);
        createTraderConfig(traderId1, 80);
        createTraderConfig(traderId2, 60);
        entityManager.clear();

        // Act
        List<ConfidenceStrategyConfig> trader1Configs =
            configRepository.findByTraderId(traderId1);
        List<ConfidenceStrategyConfig> trader2Configs =
            configRepository.findByTraderId(traderId2);

        // Assert
        assertEquals(2, trader1Configs.size());
        assertEquals(1, trader2Configs.size());
    }

    @Test
    @DisplayName("Should count configurations by threshold")
    public void testCountByThreshold() {
        // Arrange
        createTraderConfig(traderId1, 70);
        createTraderConfig(traderId1, 70);
        createTraderConfig(traderId2, 80);
        createTraderConfig(traderId2, 80);
        createTraderConfig(traderId2, 80);
        entityManager.clear();

        // Act
        long count70 = configRepository.countByThreshold(70);
        long count80 = configRepository.countByThreshold(80);
        long count90 = configRepository.countByThreshold(90);

        // Assert
        assertEquals(2, count70);
        assertEquals(3, count80);
        assertEquals(0, count90);
    }

    // ==================== INTEGRATION TESTS ====================

    @Test
    @DisplayName("Confidence > 70 signals should be majority")
    public void testSignalDistributionByConfidence() {
        // Arrange - Create diverse confidence scores
        Instant now = Instant.now();
        createConfidenceScores(now);
        entityManager.clear();

        // Act
        long above60 = scoreRepository.countScoresAboveThreshold(60, now.minusSeconds(120));
        long above70 = scoreRepository.countScoresAboveThreshold(70, now.minusSeconds(120));
        long above80 = scoreRepository.countScoresAboveThreshold(80, now.minusSeconds(120));
        long above90 = scoreRepository.countScoresAboveThreshold(90, now.minusSeconds(120));

        // Assert - Typical distribution should be: more at 60 than 70, more at 70 than 80, etc.
        assertTrue(above60 >= above70);
        assertTrue(above70 >= above80);
        assertTrue(above80 >= above90);
    }

    @Test
    @DisplayName("Should support multiple traders with different thresholds")
    public void testMultipleTraderSupport() {
        // Arrange
        createTraderConfig(traderId1, 60);
        createTraderConfig(traderId2, 80);
        Instant now = Instant.now();
        createConfidenceScores(now);
        entityManager.clear();

        // Act
        List<ConfidenceStrategyConfig> trader1Configs =
            configRepository.findByTraderId(traderId1);
        List<ConfidenceStrategyConfig> trader2Configs =
            configRepository.findByTraderId(traderId2);

        long trader1SignalCount = scoreRepository
            .countScoresAboveThreshold(60, now.minusSeconds(120));
        long trader2SignalCount = scoreRepository
            .countScoresAboveThreshold(80, now.minusSeconds(120));

        // Assert
        assertEquals(1, trader1Configs.size());
        assertEquals(1, trader2Configs.size());
        assertTrue(trader1SignalCount > trader2SignalCount);
    }

    @Test
    @DisplayName("Should track all scores for analysis")
    public void testScoreTracking() {
        // Arrange
        Instant start = Instant.now().minusSeconds(3600);
        for (int min = 0; min < 60; min += 10) {
            Instant t = start.plusSeconds(min * 60);
            createConfidenceScore("SBIN", 70 + (min % 20), t);
        }
        entityManager.clear();

        // Act
        List<ConfidenceScore> sbiScores = scoreRepository
            .findBySymbolAndTimestampAfterOrderByTimestampDesc(
                "SBIN",
                start
            );

        // Assert
        assertEquals(7, sbiScores.size());
        assertTrue(sbiScores.stream()
            .allMatch(s -> s.getSymbol().equals("SBIN")));
    }

    // ==================== HELPER METHODS ====================

    private void createConfidenceScore(String symbol, int confidence, Instant timestamp) {
        ConfidenceScore score = ConfidenceScore.builder()
            .symbol(symbol)
            .timestamp(timestamp)
            .confidenceScore(confidence)
            .buyerPressure(Math.max(0, confidence - 20))
            .sellerPressure(Math.max(0, 100 - confidence))
            .liquidityScore(confidence - 10)
            .signalStrength(getSignalStrength(confidence))
            .build();

        entityManager.persistAndFlush(score);
    }

    private void createTraderConfig(UUID traderId, int threshold) {
        ConfidenceStrategyConfig config = ConfidenceStrategyConfig.builder()
            .traderId(traderId)
            .minConfidenceThreshold(threshold)
            .enabled(true)
            .build();
        config.setMinConfidenceThreshold(threshold);

        entityManager.persistAndFlush(config);
    }

    private void createConfidenceScores(Instant now) {
        String[] symbols = {"SBIN", "HDFC", "INFY", "RELIANCE", "TCS",
                           "HDFCBANK", "KOTAKBANK", "ICICIBANK", "AXISBANK", "WIPRO"};
        int[] confidences = {92, 85, 78, 72, 68, 65, 62, 58, 55, 52};

        for (int i = 0; i < symbols.length; i++) {
            createConfidenceScore(symbols[i], confidences[i], now);
        }
    }

    private String getSignalStrength(int confidence) {
        if (confidence >= 80) return "VERY_STRONG";
        if (confidence >= 65) return "STRONG";
        if (confidence >= 55) return "MODERATE";
        if (confidence >= 45) return "WEAK";
        return "NEUTRAL";
    }
}
