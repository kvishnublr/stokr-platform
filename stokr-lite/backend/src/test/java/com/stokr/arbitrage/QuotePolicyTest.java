package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotePolicyTest {

    private static OptionChainService.OptionQuote q(double ltp, double bid, double ask) {
        OptionChainService.OptionQuote q = new OptionChainService.OptionQuote();
        q.symbol = "NIFTY26OCT24000CE";
        q.lastPrice = ltp;
        q.bid = bid;
        q.ask = ask;
        return q;
    }

    @Test
    void marketOpenRequiresTwoSidedQuote() {
        assertNotNull(QuotePolicy.usable(q(100, 99.5, 100.5), true));
        assertNull(QuotePolicy.usable(q(100, 0, 100.5), true), "missing bid");
        assertNull(QuotePolicy.usable(q(100, 99.5, 0), true), "missing ask");
        assertNull(QuotePolicy.usable(q(100, 101, 100), true), "crossed book");
    }

    @Test
    void marketClosedPricesBothSidesAtLtp() {
        OptionChainService.OptionQuote c = QuotePolicy.usable(q(100, 0, 0), false);
        assertNotNull(c);
        assertEquals(100, c.bid);
        assertEquals(100, c.ask);
        // Stale after-hours depth is ignored too
        OptionChainService.OptionQuote d = QuotePolicy.usable(q(100, 80, 120), false);
        assertEquals(100, d.bid);
        assertEquals(100, d.ask);
    }

    @Test
    void neverMutatesTheSharedCachedQuote() {
        OptionChainService.OptionQuote original = q(100, 0, 0);
        OptionChainService.OptionQuote copy = QuotePolicy.usable(original, false);
        assertNotSame(original, copy);
        assertEquals(0, original.bid);
        assertEquals(0, original.ask);
    }

    @Test
    void rejectsMissingOrUntradedQuotes() {
        assertNull(QuotePolicy.usable(null, true));
        assertNull(QuotePolicy.usable(q(0, 5, 6), false));
    }
}
