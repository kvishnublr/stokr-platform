package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Expired index options settle at intrinsic value, not at "worthless" or a stale quote. */
class ExpirySettlementTest {

    private static OptionChainService.OptionQuote quote(double ltp) {
        OptionChainService.OptionQuote q = new OptionChainService.OptionQuote();
        q.lastPrice = ltp;
        return q;
    }

    @Test
    void intrinsicFromLegFieldsOrSymbol() {
        assertEquals(120, OptionArbAutoExecService.intrinsicValue(Map.of("strike", 25000, "optionType", "CE"), 25120), 1e-9);
        assertEquals(0, OptionArbAutoExecService.intrinsicValue(Map.of("strike", 25000, "optionType", "PE"), 25120), 1e-9);
        assertEquals(80, OptionArbAutoExecService.intrinsicValue(Map.of("symbol", "NIFTY26OCT25200PE"), 25120), 1e-9);
        assertEquals(-1, OptionArbAutoExecService.intrinsicValue(Map.of("symbol", "NIFTY26OCT25200PE"), 0), 1e-9);
    }

    @Test
    void expiryDayUsesIntrinsicEvenWithoutQuotes() {
        Map<String, Object> leg = Map.of("symbol", "NIFTY26OCT25000CE", "side", "SELL", "price", 100.0, "qty", 1);
        assertEquals(150, OptionArbAutoExecService.settlementPrice(leg, Map.of(), 25150, true), 1e-9);
    }

    @Test
    void olderExpiryPrefersLastQuote() {
        Map<String, Object> leg = Map.of("symbol", "NIFTY26OCT25000CE", "side", "SELL", "price", 100.0, "qty", 1);
        assertEquals(42, OptionArbAutoExecService.settlementPrice(leg, Map.of("NIFTY26OCT25000CE", quote(42)), 25150, false), 1e-9);
        assertEquals(150, OptionArbAutoExecService.settlementPrice(leg, Map.of(), 25150, false), 1e-9);
    }

    @Test
    void shortCallInTheMoneyLosesInsteadOfKeepingPremium() {
        // Previously a leg with no quote was treated as expired worthless: +100/unit here.
        LivePosition pos = new LivePosition();
        pos.setUnderlying("NIFTY");
        pos.setLotSize(75);
        pos.setLots(1);
        pos.setLegs(List.of(Map.of("symbol", "NIFTY26OCT25000CE", "side", "SELL", "price", 100.0, "qty", 1)));
        OptionArbAutoExecService svc = new OptionArbAutoExecService(null, null, null, null, null, null, null);
        assertEquals((100 - 150) * 75, svc.computeExpiryPnl(pos, Map.of(), 25150, true), 1e-9);
    }
}
