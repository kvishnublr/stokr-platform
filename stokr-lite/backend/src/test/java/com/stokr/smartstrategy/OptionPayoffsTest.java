package com.stokr.smartstrategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks every closed-form payoff figure against a brute-force leg-by-leg expiry payoff, so the
 * formulas cannot drift from the structures the scanners actually build.
 */
class OptionPayoffsTest {

    record Leg(boolean call, int strike, int qty /* + long, − short */) {}

    /** Expiry P&amp;L per unit, before the entry cost/credit. */
    static double intrinsic(List<Leg> legs, double s) {
        double v = 0;
        for (Leg l : legs) {
            double iv = l.call ? Math.max(0, s - l.strike) : Math.max(0, l.strike - s);
            v += l.qty * iv;
        }
        return v;
    }

    static double minOver(List<Leg> legs, double cost, double from, double to) {
        double m = Double.MAX_VALUE;
        for (double s = from; s <= to; s += 0.5) m = Math.min(m, intrinsic(legs, s) - cost);
        return m;
    }

    static double maxOver(List<Leg> legs, double cost, double from, double to) {
        double m = -Double.MAX_VALUE;
        for (double s = from; s <= to; s += 0.5) m = Math.max(m, intrinsic(legs, s) - cost);
        return m;
    }

    // ---------- Ratio butterfly: BUY 1 k0, SELL 3 k1, BUY 2 k2 ----------

    @Test
    void ratioButterflyCallDebit() {
        int k0 = 24000, k1 = 24100, k2 = 24200;
        double cost = 12;
        List<Leg> legs = List.of(new Leg(true, k0, 1), new Leg(true, k1, -3), new Leg(true, k2, 2));

        assertEquals(-minOver(legs, cost, 23000, 25500), OptionPayoffs.ratioButterflyMaxLoss(k0, k1, cost), 1e-9);
        assertEquals(100 - cost, maxOver(legs, cost, 23000, 25500), 1e-9);

        OptionPayoffs.Range be = OptionPayoffs.ratioButterflyBreakevens(true, k0, k1, cost);
        assertEquals(0, intrinsic(legs, be.low()) - cost, 1e-9);
        assertEquals(0, intrinsic(legs, be.high()) - cost, 1e-9);
        // Old formula (farStrike − cost) was wrong: the payoff there is a loss.
        assertTrue(intrinsic(legs, k2 - cost) - cost < 0);
    }

    @Test
    void ratioButterflyPutCredit() {
        int k0 = 24000, k1 = 23900, k2 = 23800;
        double cost = -8; // net credit
        List<Leg> legs = List.of(new Leg(false, k0, 1), new Leg(false, k1, -3), new Leg(false, k2, 2));

        assertEquals(-minOver(legs, cost, 22500, 25000), OptionPayoffs.ratioButterflyMaxLoss(k0, k1, cost), 1e-9);

        OptionPayoffs.Range be = OptionPayoffs.ratioButterflyBreakevens(false, k0, k1, cost);
        assertTrue(Double.isNaN(be.high()), "a credit keeps the far-OTM side profitable");
        assertEquals(0, intrinsic(legs, be.low()) - cost, 1e-9);
        assertTrue(intrinsic(legs, 25000) - cost > 0);
    }

    // ---------- Broken wing butterfly (credit) ----------

    @Test
    void brokenWingPut() {
        int body = 23900, near = 24000, far = 23700; // narrow 100 above, wide 200 below
        double credit = 15;
        List<Leg> legs = List.of(new Leg(false, near, 1), new Leg(false, body, -2), new Leg(false, far, 1));

        assertEquals(-minOver(legs, -credit, 22500, 25000), OptionPayoffs.bwbMaxLoss(100, 200, credit), 1e-9);
        assertEquals(maxOver(legs, -credit, 22500, 25000), OptionPayoffs.bwbMaxProfit(100, credit), 1e-9);
        double be = OptionPayoffs.bwbBreakeven(false, body, 100, credit);
        assertEquals(0, intrinsic(legs, be) + credit, 1e-9);
        assertEquals(credit, intrinsic(legs, 25000) + credit, 1e-9, "upside keeps the credit");
    }

    @Test
    void brokenWingCall() {
        int body = 24100, near = 24000, far = 24300;
        double credit = 10;
        List<Leg> legs = List.of(new Leg(true, near, 1), new Leg(true, body, -2), new Leg(true, far, 1));

        assertEquals(-minOver(legs, -credit, 23000, 25500), OptionPayoffs.bwbMaxLoss(100, 200, credit), 1e-9);
        double be = OptionPayoffs.bwbBreakeven(true, body, 100, credit);
        assertEquals(0, intrinsic(legs, be) + credit, 1e-9);
    }

    // ---------- Skew harvest: SELL put spread + BUY call spread ----------

    @Test
    void skewHarvestScenariosIncludeCallDebit() {
        int ps = 23800, pb = 23750, cb = 24200, cs = 24250;
        double putCredit = 9, callDebit = 14; // net debit 5
        List<Leg> legs = List.of(new Leg(false, ps, -1), new Leg(false, pb, 1),
            new Leg(true, cb, 1), new Leg(true, cs, -1));
        double cost = callDebit - putCredit;

        OptionPayoffs.Scenarios sc = OptionPayoffs.skewHarvest(50, 50, putCredit, callDebit);
        assertEquals(intrinsic(legs, 23000) - cost, sc.down(), 1e-9);
        assertEquals(intrinsic(legs, 24000) - cost, sc.flat(), 1e-9);
        assertEquals(intrinsic(legs, 25000) - cost, sc.up(), 1e-9);
        assertTrue(sc.flat() < 0, "a net-debit skew trade loses when flat");

        double be = OptionPayoffs.skewHarvestBreakeven(ps, cb, putCredit, callDebit);
        assertEquals(0, intrinsic(legs, be) - cost, 1e-9);
    }

    @Test
    void skewHarvestNetCreditBreakevenSitsInPutSpread() {
        int ps = 23800, pb = 23750, cb = 24200, cs = 24250;
        double putCredit = 14, callDebit = 9; // net credit 5
        List<Leg> legs = List.of(new Leg(false, ps, -1), new Leg(false, pb, 1),
            new Leg(true, cb, 1), new Leg(true, cs, -1));
        double be = OptionPayoffs.skewHarvestBreakeven(ps, cb, putCredit, callDebit);
        assertTrue(be > pb && be < ps);
        assertEquals(0, intrinsic(legs, be) - (callDebit - putCredit), 1e-9);
    }
}
