package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RealizedVolTest {

    @Test
    void flatPricesHaveZeroVol() {
        assertEquals(0, IVRankService.realizedVol(List.of(100.0, 100.0, 100.0, 100.0)), 1e-12);
    }

    @Test
    void alternatingOnePercentMovesAnnualiseToAboutSixteenPercent() {
        List<Double> closes = new ArrayList<>();
        double p = 24000;
        for (int i = 0; i < 21; i++) {
            closes.add(p);
            p *= (i % 2 == 0) ? 1.01 : 1 / 1.01;
        }
        double rv = IVRankService.realizedVol(closes);
        assertEquals(0.01 * Math.sqrt(252), rv, 0.01);
    }

    @Test
    void notEnoughHistoryReturnsZero() {
        assertEquals(0, IVRankService.realizedVol(List.of(100.0, 101.0)));
        assertEquals(0, IVRankService.realizedVol(null));
    }
}
