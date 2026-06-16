package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.IntelligenceScore;
import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import com.stokr.intraday.metrics.repository.IntelligenceScoreRepository;
import com.stokr.intraday.metrics.repository.OrderFlowSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Disabled("Never ran since creation: @DataJpaTest slice has no @SpringBootConfiguration in this module, "
        + "no embedded DB dependency, and OrderFlowMetricsService requires a RedisTemplate the slice cannot provide. "
        + "Needs a rewrite as Mockito unit tests or a Testcontainers integration suite.")
@DataJpaTest
@Import({OrderFlowMetricsService.class, OrderFlowBatchUpdateService.class, OrderFlowValidationService.class})
@ActiveProfiles("test")
@DisplayName("Order Flow Batch Update Service Tests")
public class OrderFlowBatchUpdateServiceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderFlowSnapshotRepository snapshotRepository;

    @Autowired
    private IntelligenceScoreRepository scoreRepository;

    @Autowired
    private OrderFlowBatchUpdateService batchService;

    private OrderFlowSnapshot snapshot1;
    private OrderFlowSnapshot snapshot2;

    @BeforeEach
    public void setUp() {
        // Create test snapshot for SBIN
        snapshot1 = new OrderFlowSnapshot();
        snapshot1.setSymbol("SBIN");
        snapshot1.setTimestamp(Instant.now());
        snapshot1.setExchange("NSE");
        snapshot1.setBidPrice(BigDecimal.valueOf(590.00));
        snapshot1.setAskPrice(BigDecimal.valueOf(590.05));
        snapshot1.setBidVolume(150_000L);
        snapshot1.setAskVolume(100_000L);
        snapshot1.setBidAskRatio(BigDecimal.valueOf(1.5));
        snapshot1.setBuyerPressureScore(80);
        snapshot1.setSellerPressureScore(30);
        snapshot1.setLiquidityScore(85);
        snapshot1.setSpreadPct(BigDecimal.valueOf(0.008));
        snapshot1.setIsValid(true);

        // Create test snapshot for HDFC
        snapshot2 = new OrderFlowSnapshot();
        snapshot2.setSymbol("HDFC");
        snapshot2.setTimestamp(Instant.now());
        snapshot2.setExchange("NSE");
        snapshot2.setBidPrice(BigDecimal.valueOf(2750.00));
        snapshot2.setAskPrice(BigDecimal.valueOf(2750.50));
        snapshot2.setBidVolume(80_000L);
        snapshot2.setAskVolume(100_000L);
        snapshot2.setBidAskRatio(BigDecimal.valueOf(0.8));
        snapshot2.setBuyerPressureScore(40);
        snapshot2.setSellerPressureScore(65);
        snapshot2.setLiquidityScore(70);
        snapshot2.setSpreadPct(BigDecimal.valueOf(0.018));
        snapshot2.setIsValid(true);
    }

    @Test
    @DisplayName("Should fetch all active symbols from snapshots")
    public void testFetchActiveSymbols() {
        // Arrange
        entityManager.persistAndFlush(snapshot1);
        entityManager.persistAndFlush(snapshot2);
        entityManager.clear();

        // Act
        int activeSymbolCount = batchService.getActiveSymbolCount();

        // Assert
        assertTrue(activeSymbolCount >= 2, "Should find at least 2 active symbols");
    }

    @Test
    @DisplayName("Should update intelligence scores for all symbols")
    public void testBatchUpdate() {
        // Arrange
        entityManager.persistAndFlush(snapshot1);
        entityManager.persistAndFlush(snapshot2);
        entityManager.clear();

        // Act
        batchService.updateAllIntelligenceScores();

        // Assert
        Optional<IntelligenceScore> sbin = scoreRepository.findBySymbol("SBIN");
        Optional<IntelligenceScore> hdfc = scoreRepository.findBySymbol("HDFC");

        assertTrue(sbin.isPresent(), "SBIN intelligence score should be created");
        assertTrue(hdfc.isPresent(), "HDFC intelligence score should be created");

        // Verify SBIN score (strong buy pressure)
        IntelligenceScore sbinScore = sbin.get();
        assertEquals("STRONG_BUY_PRESSURE", sbinScore.getRecommendation());
        assertTrue(sbinScore.getConfidence() > 70, "SBIN confidence should be high");
        assertTrue(sbinScore.getShouldEnhanceConfidence(), "SBIN should be enhanced");

        // Verify HDFC score (sell pressure)
        IntelligenceScore hdfcScore = hdfc.get();
        assertEquals("SELL_PRESSURE", hdfcScore.getRecommendation());
        assertTrue(hdfcScore.getConfidence() > 40, "HDFC should have reasonable confidence");
    }

    @Test
    @DisplayName("Should store buyer pressure scores correctly")
    public void testBuyerPressureScoring() {
        // Arrange
        entityManager.persistAndFlush(snapshot1);
        entityManager.clear();

        // Act
        batchService.updateAllIntelligenceScores();

        // Assert
        IntelligenceScore score = scoreRepository.findBySymbol("SBIN").get();
        assertEquals(80, score.getBuyerPressureScore(), "Buyer pressure should match snapshot");
    }

    @Test
    @DisplayName("Should store liquidity scores correctly")
    public void testLiquidityScoring() {
        // Arrange
        entityManager.persistAndFlush(snapshot1);
        entityManager.clear();

        // Act
        batchService.updateAllIntelligenceScores();

        // Assert
        IntelligenceScore score = scoreRepository.findBySymbol("SBIN").get();
        assertEquals(85, score.getLiquidityScore(), "Liquidity should match snapshot");
    }

    @Test
    @DisplayName("Should update existing scores on re-run")
    public void testScoreUpdate() {
        // Arrange - Initial save
        entityManager.persistAndFlush(snapshot1);
        entityManager.clear();
        batchService.updateAllIntelligenceScores();

        // Get initial score
        IntelligenceScore initialScore = scoreRepository.findBySymbol("SBIN").get();
        Instant initialUpdateTime = initialScore.getLastUpdated();

        // Modify snapshot
        snapshot1.setBuyerPressureScore(95);
        entityManager.persistAndFlush(snapshot1);
        entityManager.clear();

        // Act - Re-run batch
        batchService.updateAllIntelligenceScores();

        // Assert
        IntelligenceScore updatedScore = scoreRepository.findBySymbol("SBIN").get();
        assertEquals(95, updatedScore.getBuyerPressureScore(), "Score should be updated");
        assertTrue(updatedScore.getLastUpdated().isAfter(initialUpdateTime), "Update time should be newer");
    }

    @Test
    @DisplayName("Should skip invalid snapshots")
    public void testInvalidSnapshotIgnored() {
        // Arrange
        snapshot1.setIsValid(false);  // Mark as invalid
        entityManager.persistAndFlush(snapshot1);
        entityManager.persistAndFlush(snapshot2);  // Valid
        entityManager.clear();

        // Act
        batchService.updateAllIntelligenceScores();

        // Assert
        Optional<IntelligenceScore> sbin = scoreRepository.findBySymbol("SBIN");
        Optional<IntelligenceScore> hdfc = scoreRepository.findBySymbol("HDFC");

        assertFalse(sbin.isPresent(), "Invalid SBIN should not create score");
        assertTrue(hdfc.isPresent(), "Valid HDFC should create score");
    }

    @Test
    @DisplayName("Should handle empty symbol list gracefully")
    public void testEmptySymbolList() {
        // Act & Assert - should not throw exception
        assertDoesNotThrow(() -> batchService.updateAllIntelligenceScores());
    }

    @Test
    @DisplayName("Should set last_updated timestamp")
    public void testLastUpdatedTimestamp() {
        // Arrange
        entityManager.persistAndFlush(snapshot1);
        entityManager.clear();

        // Act
        Instant beforeUpdate = Instant.now();
        batchService.updateAllIntelligenceScores();
        Instant afterUpdate = Instant.now();

        // Assert
        IntelligenceScore score = scoreRepository.findBySymbol("SBIN").get();
        assertTrue(score.getLastUpdated().isAfter(beforeUpdate), "Should be updated after start");
        assertTrue(score.getLastUpdated().isBefore(afterUpdate.plusSeconds(1)), "Should be updated before end");
    }

    @Test
    @DisplayName("Should process multiple symbols in single batch")
    public void testMultipleSymbolBatch() {
        // Arrange - Create 5 different symbols
        for (int i = 0; i < 5; i++) {
            OrderFlowSnapshot snap = new OrderFlowSnapshot();
            snap.setSymbol("STOCK" + i);
            snap.setTimestamp(Instant.now());
            snap.setExchange("NSE");
            snap.setBidPrice(BigDecimal.valueOf(100 + i));
            snap.setAskPrice(BigDecimal.valueOf(100 + i + 0.05));
            snap.setBidVolume(100_000L + (i * 10_000L));
            snap.setAskVolume(90_000L + (i * 10_000L));
            snap.setBidAskRatio(BigDecimal.valueOf(1.1));
            snap.setBuyerPressureScore(60 + i);
            snap.setSellerPressureScore(40 - i);
            snap.setLiquidityScore(65);
            snap.setSpreadPct(BigDecimal.valueOf(0.005));
            snap.setIsValid(true);
            entityManager.persistAndFlush(snap);
        }
        entityManager.clear();

        // Act
        batchService.updateAllIntelligenceScores();

        // Assert
        List<IntelligenceScore> allScores = scoreRepository.findAll();
        assertTrue(allScores.size() >= 5, "Should have at least 5 scores");
    }
}
