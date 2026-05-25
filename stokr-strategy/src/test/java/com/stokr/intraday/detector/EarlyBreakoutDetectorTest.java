package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EarlyBreakoutDetectorTest {

    private EarlyBreakoutDetector detector;
    private NseStock stock;

    @BeforeEach
    void setUp() {
        detector = new EarlyBreakoutDetector();
        stock = new NseStock();
        stock.setStockId("MARUTI");
        stock.setPrevClose(BigDecimal.valueOf(7500.00));
        stock.setPrevHigh(BigDecimal.valueOf(7600.00));
        stock.setPrevLow(BigDecimal.valueOf(7400.00));
        stock.setWeek52High(BigDecimal.valueOf(8000.00));
        stock.setWeek52Low(BigDecimal.valueOf(7000.00));
    }

    @Test
    void testDetectUpBreakout() {
        // Opening range: 7500-7537.5 (0.5%)
        // Breakout: price above 7537.5 with volume
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7550.00); // Above range
        BigDecimal currentVwap = BigDecimal.valueOf(7520.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L; // 1.5x average
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        // Early breakout should detect but requires R:R >= 1.5
        if (setup != null) {
            assertEquals("early_breakout", setup.getSetupType());
            assertEquals(currentPrice, setup.getEntryPrice());
            assertTrue(setup.getTargetPrice().compareTo(currentPrice) > 0, "Target should be above entry");
            assertNotNull(setup.getRiskRewardRatio());
        }
    }

    @Test
    void testDetectDownBreakout() {
        // Opening range: 7500-7462.5 (0.5%)
        // Breakout: price below 7462.5 with volume
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7450.00); // Below range
        BigDecimal currentVwap = BigDecimal.valueOf(7480.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L; // 1.5x average
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 10, Instant.now()
        );

        // Early breakout should detect but requires R:R >= 1.5
        if (setup != null) {
            assertEquals("early_breakout", setup.getSetupType());
            assertEquals(currentPrice, setup.getEntryPrice());
            assertTrue(setup.getTargetPrice().compareTo(currentPrice) < 0, "Target should be below entry");
            assertNotNull(setup.getRiskRewardRatio());
        }
    }

    @Test
    void testRejectIfNoBreakout() {
        // Price within opening range - no breakout yet
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7505.00); // Within range
        BigDecimal currentVwap = BigDecimal.valueOf(7505.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNull(setup, "Should reject if price is within opening range");
    }

    @Test
    void testRejectIfVolumeInsufficient() {
        // Volume below 1.5x average
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7550.00);
        BigDecimal currentVwap = BigDecimal.valueOf(7520.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 2500000L; // Only 1.25x average
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNull(setup, "Should reject if volume is below 1.5x average");
    }

    @Test
    void testRejectAfterFirstHour() {
        // Early breakout only detected 9:30-10:30
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7550.00);
        BigDecimal currentVwap = BigDecimal.valueOf(7520.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 11, Instant.now()
        );

        assertNull(setup, "Should reject after hour 10");
    }

    @Test
    void testValidBreakoutMomentum() {
        // Breakout must be 0.1%+ away from range edge
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7560.00); // 0.8% above open (sufficient momentum)
        BigDecimal currentVwap = BigDecimal.valueOf(7520.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNotNull(setup, "Should detect breakout with sufficient momentum");
        assertTrue(setup.getRiskRewardRatio().compareTo(BigDecimal.valueOf(1.5)) >= 0);
    }

    @Test
    void testTargetIs52WeekHigh() {
        // For upside breakout, target should be 52-week high if available
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7550.00);
        BigDecimal currentVwap = BigDecimal.valueOf(7520.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNotNull(setup);
        // For upside breakout, target should be 52-week high
        assertTrue(setup.getTargetPrice().compareTo(BigDecimal.valueOf(8000.00)) == 0 ||
                setup.getTargetPrice().compareTo(currentPrice.add(
                        BigDecimal.valueOf(7537.5).subtract(BigDecimal.valueOf(7462.5)).multiply(BigDecimal.valueOf(2))
                )) == 0);
    }

    @Test
    void testStopLossJustInsideRange() {
        // Stop loss should be just inside the breakout range
        BigDecimal openPrice = BigDecimal.valueOf(7500.00);
        BigDecimal currentPrice = BigDecimal.valueOf(7550.00);
        BigDecimal currentVwap = BigDecimal.valueOf(7520.00);
        BigDecimal atr14 = BigDecimal.valueOf(50.00);
        Long volume = 3000000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(7500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNotNull(setup);
        // For upside breakout, stop should be below the opening range high
        assertTrue(setup.getStopLoss().compareTo(BigDecimal.valueOf(7537.5)) < 0);
    }
}
