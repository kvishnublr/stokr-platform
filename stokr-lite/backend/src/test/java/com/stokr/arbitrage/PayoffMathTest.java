package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayoffMathTest {

    private static Map<String, Object> leg(int k, String type, String side, int qty, double price) {
        return Map.of("strike", k, "optionType", type, "side", side, "qty", qty, "price", price);
    }

    @Test
    void ironCondorMaxLossIsWingWidthMinusCredit() {
        // Short 55000/55500 strangle with 300-wide wings, credit 60 + 50 - 20 - 15 = 75.
        var legs = List.of(leg(54700, "PE", "BUY", 1, 15), leg(55000, "PE", "SELL", 1, 50),
                           leg(55500, "CE", "SELL", 1, 60), leg(55800, "CE", "BUY", 1, 20));
        assertEquals(300 - 75, PayoffMath.maxLossPerUnit(legs), 1e-9);
    }

    @Test
    void longButterflyMaxLossIsTheDebit() {
        var legs = List.of(leg(22800, "CE", "BUY", 1, 180), leg(22900, "CE", "SELL", 2, 120), leg(23000, "CE", "BUY", 1, 75));
        assertEquals(180 - 240 + 75, PayoffMath.maxLossPerUnit(legs), 1e-9);
    }

    @Test
    void jadeLizardDownsideIsTheWidePutSpread() {
        // 21-Oct #266 shape: 55600/54600 put spread vs a 300-wide call spread.
        var legs = List.of(leg(54600, "PE", "BUY", 1, 40), leg(55600, "PE", "SELL", 1, 250),
                           leg(56000, "CE", "SELL", 1, 90), leg(56300, "CE", "BUY", 1, 40));
        double credit = 250 - 40 + 90 - 40;
        assertEquals(1000 - credit, PayoffMath.maxLossPerUnit(legs), 1e-9);
    }

    @Test
    void unboundedOrUncomputableIsNull() {
        assertNull(PayoffMath.maxLossPerUnit(List.of(leg(23000, "CE", "SELL", 1, 100))));        // naked call
        assertNull(PayoffMath.maxLossPerUnit(List.of(Map.of("strike", 0, "optionType", "FUT", "side", "SELL", "qty", 1, "price", 23000))));
        assertNull(PayoffMath.maxLossPerUnit(List.of(
            Map.of("strike", 23000, "optionType", "CE", "side", "SELL", "qty", 1, "price", 100, "expiry", "2026-10-27"),
            Map.of("strike", 23000, "optionType", "CE", "side", "BUY", "qty", 1, "price", 160, "expiry", "2026-11-24"))));
        assertNull(PayoffMath.maxLossPerUnit(List.of()));
    }

    @Test
    void effectiveMaxLossPrefersLegPayoffOverStoredField() {
        LivePosition p = new LivePosition();
        p.setLotSize(30); p.setLots(2);
        p.setMaxLossAmount(99999.0);
        p.setLegs(List.of(leg(54700, "PE", "BUY", 1, 15), leg(55000, "PE", "SELL", 1, 50),
                          leg(55500, "CE", "SELL", 1, 60), leg(55800, "CE", "BUY", 1, 20)));
        assertEquals(225 * 30 * 2, OptionArbAutoExecService.effectiveMaxLoss(p), 1e-9);
        p.setLegs(List.of(Map.of("symbol", "X", "side", "SELL", "price", 10.0)));          // no strikes: stored value
        assertEquals(99999.0, OptionArbAutoExecService.effectiveMaxLoss(p), 1e-9);
    }
}
