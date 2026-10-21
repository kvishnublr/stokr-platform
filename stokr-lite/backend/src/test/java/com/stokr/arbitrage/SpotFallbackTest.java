package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A failed spot fetch must never invent a price: recent cache or 0. */
class SpotFallbackTest {
    private static final long NOW = 1_000_000_000L;

    @Test
    void recentCacheIsReused() {
        double[] r = ZerodhaSpotPriceFetcher.fallback(23050.0, NOW - 30_000, 23110.0, NOW - 30_000, NOW);
        assertArrayEquals(new double[]{23050.0, 23110.0}, r);
    }

    @Test
    void oldCacheOrNothingGivesZeroNotAHardCodedIndexLevel() {
        assertArrayEquals(new double[]{0, 0}, ZerodhaSpotPriceFetcher.fallback(23050.0, NOW - 3_600_000, null, null, NOW));
        assertArrayEquals(new double[]{0, 0}, ZerodhaSpotPriceFetcher.fallback(null, null, null, null, NOW));
    }

    @Test
    void futureIsNeverFilledWithTheSpot() {
        assertArrayEquals(new double[]{23050.0, 0}, ZerodhaSpotPriceFetcher.fallback(23050.0, NOW - 1_000, null, null, NOW));
    }
}
