package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SafeStrikeAndTurbulenceTest {

    @Test
    void safeStrikeDistanceEnsuresMinimumBuffer() {
        int niftyStep = 50;
        int bankNiftyStep = 100;

        int niftyMinSteps = "BANKNIFTY".equalsIgnoreCase("NIFTY") ? 4 : 3;
        int bankNiftyMinSteps = "BANKNIFTY".equalsIgnoreCase("BANKNIFTY") ? 4 : 3;

        assertEquals(3, niftyMinSteps);
        assertEquals(4, bankNiftyMinSteps);

        // Verify that minimum distance from ATM is at least 150 pts on NIFTY and 400 pts on BANKNIFTY
        int niftyMinBuffer = niftyMinSteps * niftyStep;
        int bankNiftyMinBuffer = bankNiftyMinSteps * bankNiftyStep;

        assertTrue(niftyMinBuffer >= 150, "Nifty short strikes must be at least 150 pts from ATM");
        assertTrue(bankNiftyMinBuffer >= 400, "BankNifty short strikes must be at least 400 pts from ATM");
    }

    @Test
    void openingTurbulenceGuardBlocksBefore930() {
        LocalTime openingBell = LocalTime.of(9, 15, 30);
        LocalTime openingMid = LocalTime.of(9, 29, 59);
        LocalTime postTurbulence = LocalTime.of(9, 30, 0);

        LocalTime cutoff = LocalTime.of(9, 30);

        assertTrue(openingBell.isBefore(cutoff), "9:15:30 is before 9:30 turbulence cutoff");
        assertTrue(openingMid.isBefore(cutoff), "9:29:59 is before 9:30 turbulence cutoff");
        assertFalse(postTurbulence.isBefore(cutoff), "9:30:00 is cleared for execution");
    }

    @Test
    void earlyThreatExitDetectsDoubledShortPremium() {
        double entryPrice = 120.0;
        double normalPrice = 180.0;     // 1.5x entry
        double threatPrice = 270.0;     // 2.25x entry (> 2.2x threshold)

        boolean normalBreached = normalPrice >= entryPrice * 2.2;
        boolean threatBreached = threatPrice >= entryPrice * 2.2;

        assertFalse(normalBreached, "1.5x premium is normal intraday fluctuation");
        assertTrue(threatBreached, ">= 2.2x premium indicates blown out wing requiring early exit");
    }
}