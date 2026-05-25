package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GapFillDetectorTest {

    private GapFillDetector detector;
    private NseStock stock;

    @BeforeEach
    void setUp() {
        detector = new GapFillDetector();
        stock = new NseStock();
        stock.setStockId("INFY");
        stock.setPrevClose(BigDecimal.valueOf(1500.00));
        stock.setPrevHigh(BigDecimal.valueOf(1510.00));
        stock.setPrevLow(BigDecimal.valueOf(1480.00));
        stock.setWeek52High(BigDecimal.valueOf(1600.00));
        stock.setWeek52Low(BigDecimal.valueOf(1400.00));
    }

    @Test
    void testDetectGapUpFill() {
        // Gap up scenario: stock opens at 1530 (2% gap up from 1500)
        BigDecimal openPrice = BigDecimal.valueOf(1530.00);
        BigDecimal currentPrice = BigDecimal.valueOf(1505.00); // Near prev close (within 1%)
        BigDecimal currentVwap = BigDecimal.valueOf(1510.00);
        BigDecimal atr14 = BigDecimal.valueOf(20.00);
        Long volume = 2000000L;
        Long avgVolume = 1500000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(1500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNotNull(setup, "Should detect gap up fill");
        assertEquals("gap_fill", setup.getSetupType());
        assertEquals(BigDecimal.valueOf(1500.00), setup.getEntryPrice());
        assertEquals(BigDecimal.valueOf(1480.00), setup.getTargetPrice()); // prev low for gap up
        assertNotNull(setup.getRiskRewardRatio());
        assertTrue(setup.getRiskRewardRatio().compareTo(BigDecimal.valueOf(1.5)) >= 0);
    }

    @Test
    void testDetectGapDownFill() {
        // Gap down scenario: stock opens at 1470 (2% gap down from 1500)
        BigDecimal openPrice = BigDecimal.valueOf(1470.00);
        BigDecimal currentPrice = BigDecimal.valueOf(1498.00); // Near prev close (within 1%)
        BigDecimal currentVwap = BigDecimal.valueOf(1490.00);
        BigDecimal atr14 = BigDecimal.valueOf(20.00);
        Long volume = 2000000L;
        Long avgVolume = 1500000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(1500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNotNull(setup, "Should detect gap down fill");
        assertEquals("gap_fill", setup.getSetupType());
        assertEquals(BigDecimal.valueOf(1500.00), setup.getEntryPrice());
        assertEquals(BigDecimal.valueOf(1510.00), setup.getTargetPrice()); // prev high for gap down
        assertNotNull(setup.getRiskRewardRatio());
    }

    @Test
    void testRejectSmallGap() {
        // Small gap (0.1%) - should be rejected
        BigDecimal openPrice = BigDecimal.valueOf(1501.50);
        BigDecimal currentPrice = BigDecimal.valueOf(1500.50);
        BigDecimal currentVwap = BigDecimal.valueOf(1500.75);
        BigDecimal atr14 = BigDecimal.valueOf(20.00);

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(1500.00), 1500000L, 1500000L, atr14, 9, Instant.now()
        );

        assertNull(setup, "Should reject small gap (<0.3%)");
    }

    @Test
    void testRejectOutsideFirstHour() {
        // Detect outside 9-10 hour window
        BigDecimal openPrice = BigDecimal.valueOf(1530.00);
        BigDecimal currentPrice = BigDecimal.valueOf(1505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(1510.00);
        BigDecimal atr14 = BigDecimal.valueOf(20.00);

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(1500.00), 2000000L, 1500000L, atr14, 12, Instant.now()
        );

        assertNull(setup, "Should reject gap fill detection after 10:30");
    }

    @Test
    void testValidRiskRewardRatio() {
        // Ensure setup has minimum 1.5 R:R ratio
        BigDecimal openPrice = BigDecimal.valueOf(1530.00);
        BigDecimal currentPrice = BigDecimal.valueOf(1505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(1510.00);
        BigDecimal atr14 = BigDecimal.valueOf(20.00);

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(1500.00), 2000000L, 1500000L, atr14, 9, Instant.now()
        );

        assertNotNull(setup);
        BigDecimal expectedRisk = BigDecimal.valueOf(1500.00).subtract(setup.getStopLoss()).abs();
        BigDecimal expectedReward = setup.getTargetPrice().subtract(BigDecimal.valueOf(1500.00)).abs();
        BigDecimal expectedRatio = expectedReward.divide(expectedRisk, 2, java.math.RoundingMode.HALF_UP);
        assertTrue(expectedRatio.compareTo(BigDecimal.valueOf(1.5)) >= 0);
    }

    @Test
    void testExpirySetCorrectly() {
        BigDecimal openPrice = BigDecimal.valueOf(1530.00);
        BigDecimal currentPrice = BigDecimal.valueOf(1505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(1510.00);
        Instant now = Instant.now();

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(1500.00), 2000000L, 1500000L, BigDecimal.valueOf(20.00), 9, now
        );

        assertNotNull(setup);
        assertNotNull(setup.getExpiresAt());
        // Expiry should be 30 minutes from detection
        long expectedExpiry = now.plusSeconds(30 * 60).getEpochSecond();
        long actualExpiry = setup.getExpiresAt().getEpochSecond();
        assertTrue(Math.abs(expectedExpiry - actualExpiry) < 2); // Allow 2 second tolerance
    }
}
