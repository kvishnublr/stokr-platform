package com.stokr.intraday.detector;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class VwapBounceDetectorTest {

    private VwapBounceDetector detector;
    private NseStock stock;

    @BeforeEach
    void setUp() {
        detector = new VwapBounceDetector();
        stock = new NseStock();
        stock.setStockId("TCS");
        stock.setPrevClose(BigDecimal.valueOf(3500.00));
        stock.setPrevHigh(BigDecimal.valueOf(3520.00));
        stock.setPrevLow(BigDecimal.valueOf(3480.00));
        stock.setWeek52High(BigDecimal.valueOf(3600.00));
        stock.setWeek52Low(BigDecimal.valueOf(3300.00));
    }

    @Test
    void testDetectVwapBounceUp() {
        // Price touches VWAP and bounces up
        BigDecimal currentPrice = BigDecimal.valueOf(3505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(3502.00); // Within 1%
        BigDecimal openPrice = BigDecimal.valueOf(3500.00);
        BigDecimal atr14 = BigDecimal.valueOf(25.00);
        Long volume = 2500000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(3500.00), volume, avgVolume, atr14, 11, Instant.now()
        );

        assertNotNull(setup, "Should detect VWAP bounce up");
        assertEquals("vwap_bounce", setup.getSetupType());
        assertEquals(currentVwap, setup.getEntryPrice());
        assertEquals(BigDecimal.valueOf(3520.00), setup.getTargetPrice()); // prev high
        assertNotNull(setup.getRiskRewardRatio());
    }

    @Test
    void testDetectVwapBounceDown() {
        // Price touches VWAP and bounces down
        BigDecimal currentPrice = BigDecimal.valueOf(3498.00);
        BigDecimal currentVwap = BigDecimal.valueOf(3502.00); // Within 1%
        BigDecimal openPrice = BigDecimal.valueOf(3500.00);
        BigDecimal atr14 = BigDecimal.valueOf(25.00);
        Long volume = 2500000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(3500.00), volume, avgVolume, atr14, 11, Instant.now()
        );

        assertNotNull(setup, "Should detect VWAP bounce down");
        assertEquals("vwap_bounce", setup.getSetupType());
        assertEquals(currentVwap, setup.getEntryPrice());
        assertEquals(BigDecimal.valueOf(3480.00), setup.getTargetPrice()); // prev low
        assertNotNull(setup.getRiskRewardRatio());
    }

    @Test
    void testRejectIfNotNearVwap() {
        // Price far from VWAP (>1%)
        BigDecimal currentPrice = BigDecimal.valueOf(3540.00);
        BigDecimal currentVwap = BigDecimal.valueOf(3502.00); // >1% away
        BigDecimal openPrice = BigDecimal.valueOf(3500.00);
        BigDecimal atr14 = BigDecimal.valueOf(25.00);

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(3500.00), 2500000L, 2000000L, atr14, 11, Instant.now()
        );

        assertNull(setup, "Should reject if price is far from VWAP");
    }

    @Test
    void testRejectIfVolumeInsufficient() {
        // Volume below average
        BigDecimal currentPrice = BigDecimal.valueOf(3505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(3502.00);
        BigDecimal openPrice = BigDecimal.valueOf(3500.00);
        BigDecimal atr14 = BigDecimal.valueOf(25.00);
        Long volume = 1500000L; // Below 1.1x average (2000000)
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(3500.00), volume, avgVolume, atr14, 11, Instant.now()
        );

        assertNull(setup, "Should reject if volume is insufficient");
    }

    @Test
    void testRejectBeforeHour10() {
        // Detection before hour 10
        BigDecimal currentPrice = BigDecimal.valueOf(3505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(3502.00);
        BigDecimal openPrice = BigDecimal.valueOf(3500.00);
        BigDecimal atr14 = BigDecimal.valueOf(25.00);
        Long volume = 2500000L;
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(3500.00), volume, avgVolume, atr14, 9, Instant.now()
        );

        assertNull(setup, "Should not detect VWAP bounces before hour 10");
    }

    @Test
    void testValidVolumeConfirmation() {
        // Volume is 1.5x average (strong confirmation)
        BigDecimal currentPrice = BigDecimal.valueOf(3505.00);
        BigDecimal currentVwap = BigDecimal.valueOf(3502.00);
        BigDecimal openPrice = BigDecimal.valueOf(3500.00);
        BigDecimal atr14 = BigDecimal.valueOf(25.00);
        Long volume = 3000000L; // 1.5x of 2000000
        Long avgVolume = 2000000L;

        CurrentSetup setup = detector.detectSetup(
                stock, currentPrice, currentVwap, openPrice,
                BigDecimal.valueOf(3500.00), volume, avgVolume, atr14, 11, Instant.now()
        );

        assertNotNull(setup, "Should detect with strong volume confirmation");
        assertTrue(setup.getRiskRewardRatio().compareTo(BigDecimal.valueOf(1.5)) >= 0);
    }
}
