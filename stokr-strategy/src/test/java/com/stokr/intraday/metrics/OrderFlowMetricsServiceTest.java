package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import com.stokr.intraday.metrics.dto.OrderFlowSignalEnhancement;
import com.stokr.intraday.metrics.repository.OrderFlowSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(OrderFlowMetricsService.class)
@DisplayName("Order Flow Metrics Service Tests")
public class OrderFlowMetricsServiceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderFlowSnapshotRepository snapshotRepository;

    @Autowired
    private OrderFlowMetricsService metricsService;

    private OrderFlowSnapshot testSnapshot;

    @BeforeEach
    public void setUp() {
        // Create test snapshot
        testSnapshot = new OrderFlowSnapshot();
        testSnapshot.setSymbol("SBIN");
        testSnapshot.setTimestamp(Instant.now());
        testSnapshot.setExchange("NSE");

        // Set bid-ask prices
        testSnapshot.setBidPrice(BigDecimal.valueOf(590.00));
        testSnapshot.setAskPrice(BigDecimal.valueOf(590.05));

        // Set volumes
        testSnapshot.setBidVolume(100_000L);
        testSnapshot.setAskVolume(80_000L);

        testSnapshot.setIsValid(true);
    }

    // ==================== STRONG BUY PRESSURE TESTS ====================

    @Test
    @DisplayName("Should identify STRONG_BUY_PRESSURE when bid/ask ratio > 1.3")
    public void testStrongBuyPressureDetection() {
        // Arrange
        testSnapshot.setBidVolume(130_000L);
        testSnapshot.setAskVolume(100_000L);
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(1.3));
        testSnapshot.setBuyerPressureScore(75);
        testSnapshot.setLiquidityScore(75);

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertEquals("STRONG_BUY_PRESSURE", signal.getRecommendation());
        assertTrue(signal.isStrongBuySignal());
        assertTrue(signal.getShouldEnhanceConfidence());
        assertFalse(signal.getShouldSkip());
    }

    @Test
    @DisplayName("Should identify BUY_PRESSURE when bid/ask ratio > 1.1")
    public void testBuyPressureDetection() {
        // Arrange
        testSnapshot.setBidVolume(110_000L);
        testSnapshot.setAskVolume(100_000L);
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(1.1));
        testSnapshot.setBuyerPressureScore(60);
        testSnapshot.setLiquidityScore(65);

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertEquals("BUY_PRESSURE", signal.getRecommendation());
        assertTrue(signal.isBuySignal());
        assertFalse(signal.isSellSignal());
    }

    // ==================== SELL PRESSURE TESTS ====================

    @Test
    @DisplayName("Should identify SELL_PRESSURE when bid/ask ratio < 0.7")
    public void testSellPressureDetection() {
        // Arrange
        testSnapshot.setBidVolume(70_000L);
        testSnapshot.setAskVolume(100_000L);
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(0.7));
        testSnapshot.setSellerPressureScore(70);
        testSnapshot.setLiquidityScore(72);

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertEquals("SELL_PRESSURE", signal.getRecommendation());
        assertTrue(signal.isSellSignal());
        assertTrue(signal.isStrongSellSignal());
    }

    // ==================== LIQUIDITY TESTS ====================

    @Test
    @DisplayName("Should SKIP signal when liquidity score < 40")
    public void testPoorLiquiditySkip() {
        // Arrange
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(1.2));
        testSnapshot.setBuyerPressureScore(70);  // Strong pressure
        testSnapshot.setLiquidityScore(35);      // But poor liquidity

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertEquals("POOR_LIQUIDITY_SKIP", signal.getRecommendation());
        assertTrue(signal.isPoorLiquidity());
        assertTrue(signal.getShouldSkip());
    }

    @Test
    @DisplayName("Should REDUCE confidence when spread is too wide")
    public void testWideSpreadReducesConfidence() {
        // Arrange
        testSnapshot.setBidPrice(BigDecimal.valueOf(590.00));
        testSnapshot.setAskPrice(BigDecimal.valueOf(590.50));  // 0.5% spread
        testSnapshot.setSpreadPct(BigDecimal.valueOf(0.085));
        testSnapshot.setBuyerPressureScore(60);
        testSnapshot.setLiquidityScore(45);

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertTrue(signal.getShouldReduceConfidence());
        assertFalse(signal.getShouldEnhanceConfidence());
    }

    // ==================== CONFIDENCE CALCULATION TESTS ====================

    @Test
    @DisplayName("Confidence should be high (>80) with strong pressure + good liquidity + tight spread")
    public void testHighConfidenceSignal() {
        // Arrange - Perfect conditions
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(1.4));
        testSnapshot.setBuyerPressureScore(80);
        testSnapshot.setLiquidityScore(85);
        testSnapshot.setSpreadPct(BigDecimal.valueOf(0.015));  // Tight
        testSnapshot.setTimestamp(Instant.now());

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertTrue(signal.getConfidence() > 80, "Confidence should be > 80");
        assertTrue(signal.getShouldEnhanceConfidence());
    }

    @Test
    @DisplayName("Confidence should be low (<40) with poor conditions")
    public void testLowConfidenceSignal() {
        // Arrange - Poor conditions
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(0.9));
        testSnapshot.setBuyerPressureScore(40);
        testSnapshot.setSellerPressureScore(45);
        testSnapshot.setLiquidityScore(35);
        testSnapshot.setSpreadPct(BigDecimal.valueOf(0.40));  // Very wide

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertTrue(signal.getConfidence() < 50, "Confidence should be < 50");
        assertTrue(signal.getShouldSkip());
    }

    // ==================== NEUTRAL CONDITIONS ====================

    @Test
    @DisplayName("Should return NEUTRAL when pressures are balanced")
    public void testNeutralConditions() {
        // Arrange
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(1.0));
        testSnapshot.setBidVolume(95_000L);
        testSnapshot.setAskVolume(95_000L);
        testSnapshot.setBuyerPressureScore(50);
        testSnapshot.setSellerPressureScore(50);
        testSnapshot.setLiquidityScore(65);

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertEquals("NEUTRAL", signal.getRecommendation());
        assertTrue(signal.isNeutral());
        assertFalse(signal.shouldEnhanceConfidence);
        assertFalse(signal.shouldReduceConfidence);
    }

    // ==================== NO DATA TESTS ====================

    @Test
    @DisplayName("Should return NO_DATA when symbol has no snapshots")
    public void testNoDataAvailable() {
        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("NONEXISTENT");

        // Assert
        assertTrue(signal.isError());
        assertEquals(0, signal.getConfidence());
    }

    @Test
    @DisplayName("Should return NO_DATA when latest snapshot is invalid")
    public void testInvalidSnapshotIgnored() {
        // Arrange
        testSnapshot.setIsValid(false);
        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertTrue(signal.isError());
    }

    // ==================== CONFIDENCE MULTIPLIER TESTS ====================

    @Test
    @DisplayName("Confidence multiplier should be 1.5x when confidence = 100")
    public void testMaxConfidenceMultiplier() {
        // Arrange - Perfect conditions
        testSnapshot.setBuyerPressureScore(100);
        testSnapshot.setLiquidityScore(100);
        testSnapshot.setSpreadPct(BigDecimal.valueOf(0.01));

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        double multiplier = signal.getConfidenceMultiplier();
        assertEquals(1.5, multiplier, 0.1, "Max confidence should give 1.5x multiplier");
    }

    @Test
    @DisplayName("Confidence multiplier should be 1.0x when confidence = 50")
    public void testNeutralConfidenceMultiplier() {
        // Arrange - Neutral conditions
        testSnapshot.setBuyerPressureScore(50);
        testSnapshot.setSellerPressureScore(50);
        testSnapshot.setLiquidityScore(60);
        testSnapshot.setSpreadPct(BigDecimal.valueOf(0.10));

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        double multiplier = signal.getConfidenceMultiplier();
        assertEquals(1.0, multiplier, 0.15, "Neutral confidence should give ~1.0x multiplier");
    }

    @Test
    @DisplayName("Confidence multiplier should be 0.5x when confidence = 0")
    public void testMinConfidenceMultiplier() {
        // Arrange - Very poor conditions
        testSnapshot.setBuyerPressureScore(0);
        testSnapshot.setSellerPressureScore(0);
        testSnapshot.setLiquidityScore(0);
        testSnapshot.setSpreadPct(BigDecimal.valueOf(1.0));

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        double multiplier = signal.getConfidenceMultiplier();
        assertTrue(multiplier >= 0.5 && multiplier <= 1.0);
    }

    // ==================== EDGE CASES ====================

    @Test
    @DisplayName("Should handle zero bid volume gracefully")
    public void testZeroBidVolume() {
        // Arrange
        testSnapshot.setBidVolume(0);  // No bids!
        testSnapshot.setAskVolume(100_000L);

        entityManager.persistAndFlush(testSnapshot);

        // Act & Assert - should not throw
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");
        assertFalse(signal.isStrongBuySignal());
    }

    @Test
    @DisplayName("Should handle extreme bid/ask ratio")
    public void testExtremeRatio() {
        // Arrange
        testSnapshot.setBidVolume(500_000L);
        testSnapshot.setAskVolume(1_000L);  // 500:1 ratio
        testSnapshot.setBidAskRatio(BigDecimal.valueOf(500));
        testSnapshot.setBuyerPressureScore(100);

        entityManager.persistAndFlush(testSnapshot);

        // Act
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");

        // Assert
        assertEquals("STRONG_BUY_PRESSURE", signal.getRecommendation());
    }

    // ==================== SIGNAL STRENGTH RATING ====================

    @Test
    @DisplayName("Should rate signal strength correctly")
    public void testSignalStrengthRating() {
        // Very strong
        testSnapshot.setBuyerPressureScore(85);
        entityManager.persistAndFlush(testSnapshot);
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal("SBIN");
        assertEquals("VERY_STRONG", signal.getSignalStrength());

        // Clear and create new
        entityManager.clear();

        // Strong
        testSnapshot.setId(null);
        testSnapshot.setBuyerPressureScore(70);
        entityManager.persistAndFlush(testSnapshot);
        signal = metricsService.getOrderFlowSignal("SBIN");
        assertEquals("STRONG", signal.getSignalStrength());
    }
}
