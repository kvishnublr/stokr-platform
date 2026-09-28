package com.stokr.arbitrage;

/**
 * Decides which option quotes a scanner may price from.
 *
 * <ul>
 *   <li><b>Market open</b> — only two-sided quotes (bid &gt; 0, ask &gt; 0, ask &ge; bid). A missing side
 *       means the strike cannot be traded at the shown price, so it is rejected rather than
 *       back-filled with LTP (that produced phantom edges on illiquid strikes).</li>
 *   <li><b>Market closed</b> — preview mode: bid and ask are both set to LTP so weekend/after-hours
 *       scans still show opportunities. Callers label these results as LTP based.</li>
 * </ul>
 *
 * Always returns a copy: quotes live in OptionChainService's shared cache and must not be mutated.
 */
public final class QuotePolicy {

    private QuotePolicy() {}

    public static OptionChainService.OptionQuote usable(OptionChainService.OptionQuote q, boolean marketOpen) {
        if (q == null || q.lastPrice <= 0) return null;
        OptionChainService.OptionQuote c = copy(q);
        if (marketOpen) {
            if (q.bid <= 0 || q.ask <= 0 || q.ask < q.bid) return null;
        } else {
            c.bid = q.lastPrice;
            c.ask = q.lastPrice;
        }
        return c;
    }

    private static OptionChainService.OptionQuote copy(OptionChainService.OptionQuote q) {
        OptionChainService.OptionQuote c = new OptionChainService.OptionQuote();
        c.symbol = q.symbol;
        c.lastPrice = q.lastPrice;
        c.bid = q.bid;
        c.ask = q.ask;
        c.bidQty = q.bidQty;
        c.askQty = q.askQty;
        c.volume = q.volume;
        c.openInterest = q.openInterest;
        return c;
    }
}
