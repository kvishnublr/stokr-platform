package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Paper fills and exit triggers must only use fresh two-sided quotes; fake arb edges are rejected. */
class QuoteFreshnessAndArbSanityTest {

    private static OptionChainService.OptionQuote q(double bid, double ask, long ageMs) {
        OptionChainService.OptionQuote q = new OptionChainService.OptionQuote();
        q.bid = bid; q.ask = ask; q.lastPrice = (bid + ask) / 2;
        q.fetchedAtMs = System.currentTimeMillis() - ageMs;
        return q;
    }

    @Test
    void freshTwoSidedQuoteIsTradable() {
        assertTrue(q(100, 101, 1_000).isTradable());
    }

    @Test
    void ltpOnlyStaleOrWideQuotesAreNot() {
        assertEquals("no two-sided bid/ask", q(0, 101, 1_000).notTradableReason());
        assertEquals("quote older than 15s", q(100, 101, 20_000).notTradableReason());
        assertEquals("crossed bid/ask", q(102, 101, 1_000).notTradableReason());
        assertEquals("bid/ask spread too wide", q(100, 120, 1_000).notTradableReason());
        OptionChainService.OptionQuote cached = q(100, 101, 1_000);
        cached.staleFallback = true;
        assertEquals("stale cached quote", cached.notTradableReason());
        assertEquals("quote older than 15s", new OptionChainService.OptionQuote().notTradableReason());
    }

    @Test
    void firstUntradableNamesTheBadLeg() {
        Map<String, OptionChainService.OptionQuote> quotes = Map.of("A", q(10, 10.5, 500), "B", q(0, 4, 500));
        assertNull(OptionChainService.firstUntradable(List.of("A"), quotes));
        assertEquals("B: no two-sided bid/ask", OptionChainService.firstUntradable(List.of("A", "B"), quotes));
        assertEquals("C: no quote", OptionChainService.firstUntradable(List.of("C"), quotes));
    }

    @Test
    void arbEdgesBeyondRealisticSizeAreDataErrors() {
        assertFalse(ArbSanity.implausibleEdge("BOX_SPREAD", 2.5));
        assertTrue(ArbSanity.implausibleEdge("BOX_SPREAD", 26.0));    // audit median
        assertTrue(ArbSanity.implausibleEdge("BOX_SPREAD_ARB", -27.4));
        assertFalse(ArbSanity.implausibleEdge("BID_PARITY", 4.0));
        assertTrue(ArbSanity.implausibleEdge("BID_PARITY", 56.5));
        assertFalse(ArbSanity.implausibleEdge("IRON_CONDOR", 500));
    }

    @Test
    void boxAndParityAreNeverAutoTraded() {
        assertTrue(ArbSanity.autoTradeBlockedForLive("BOX_SPREAD"));
        assertTrue(ArbSanity.autoTradeBlockedForLive("BOX_SPREAD_ARB"));
        assertTrue(ArbSanity.autoTradeBlockedForLive("BID_PARITY"));
        assertFalse(ArbSanity.autoTradeBlockedForLive("CONDOR_SPREAD"));
        assertFalse(ArbSanity.autoTradeBlockedForLive("BUTTERFLY_SPREAD"));
    }
}
