package com.stokr.arbitrage;

/**
 * Decides which option quotes a scanner may price from.
 * Enforces strict liquidity and bid-ask spread limits to protect against live execution slippage.
 */
public final class QuotePolicy {

    private QuotePolicy() {}

    public static OptionChainService.OptionQuote usable(OptionChainService.OptionQuote q, boolean marketOpen) {
        if (q == null || q.lastPrice <= 0) return null;
        OptionChainService.OptionQuote c = copy(q);
        if (marketOpen) {
            if (q.bid <= 0 || q.ask <= 0 || q.ask < q.bid) return null;

            // SOLUTION 1: Bid-Ask Spread Filter Guard
            // Reject illiquid option contracts with wide bid-ask spread
            double spreadPts = q.ask - q.bid;

            // Maximum allowed spread: 4.0 points max for BANKNIFTY, 3.0 points max for NIFTY/FINNIFTY
            boolean isBankNifty = q.symbol != null && q.symbol.contains("BANKNIFTY");
            double maxAllowedSpreadPts = isBankNifty ? 4.0 : 3.0;

            // Adaptive spread limit: allow normal tick spread (min 0.75 pts) for low premium options,
            // or 1.5% of bid, capped at maxAllowedSpreadPts.
            double allowedSpread = Math.min(maxAllowedSpreadPts, Math.max(0.75, q.bid * 0.015));

            if (spreadPts > allowedSpread) {
                return null; // Reject wide, illiquid spread quotes
            }
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
