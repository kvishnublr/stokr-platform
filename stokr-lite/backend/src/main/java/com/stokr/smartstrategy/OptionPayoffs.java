package com.stokr.smartstrategy;

/**
 * Expiry payoff formulas for the Smart Strategy structures, in index points per 1 unit.
 * Kept pure (no quotes, no Spring) so every breakeven / max-loss figure shown in the UI is unit-tested.
 * "cost" is net debit (&gt; 0) or net credit (&lt; 0) unless stated otherwise.
 */
public final class OptionPayoffs {

    private OptionPayoffs() {}

    /** [low, high] breakevens; {@link Double#NaN} means that side has no breakeven. */
    public record Range(double low, double high) {}

    /**
     * Ratio butterfly: BUY 1 @k0 (ATM), SELL 3 @k1, BUY 2 @k2, with k1 = k0 ± w and k2 = k0 ± 2w
     * (+ for CE, − for PE). Peak profit w − cost at k1; beyond k2 the position is net flat so the
     * loss is w + cost; on the far side of k0 all legs expire worthless (P&amp;L = −cost).
     */
    public static Range ratioButterflyBreakevens(boolean call, int k0, int k1, double cost) {
        double w = Math.abs(k1 - k0);
        if (call) {
            double low = cost > 0 ? k0 + cost : Double.NaN;        // credit ⇒ profitable below k0 too
            double high = k1 + (w - cost) / 2.0;                   // slope −2 between k1 and k2
            return new Range(low, high);
        }
        double low = k1 - (w - cost) / 2.0;
        double high = cost > 0 ? k0 - cost : Double.NaN;
        return new Range(low, high);
    }

    /** Ratio butterfly worst case (points): w + cost when the underlying runs past the far wing. */
    public static double ratioButterflyMaxLoss(int k0, int k1, double cost) {
        return Math.abs(k1 - k0) + cost;
    }

    /**
     * Broken-wing butterfly entered for a credit: BUY 1 near wing (narrow side), SELL 2 body, BUY 1 far
     * wing (wide side). Max profit narrow + credit at the body; worst case wide − narrow − credit
     * beyond the far wing; the narrow side keeps the credit.
     */
    public static double bwbMaxLoss(int narrowWidth, int wideWidth, double credit) {
        return wideWidth - narrowWidth - credit;
    }

    public static double bwbMaxProfit(int narrowWidth, double credit) {
        return narrowWidth + credit;
    }

    /** Single breakeven on the wide side: body ∓ (narrow + credit) (− for PE, + for CE). */
    public static double bwbBreakeven(boolean call, int bodyStrike, int narrowWidth, double credit) {
        return call ? bodyStrike + narrowWidth + credit : bodyStrike - narrowWidth - credit;
    }

    /**
     * Skew harvest: SELL put spread (credit) + BUY call spread (debit). netCost = callDebit − putCredit.
     * Returns P&amp;L (points) when the underlying ends below the put spread, between the spreads, and
     * above the call spread.
     */
    public record Scenarios(double down, double flat, double up) {}

    public static Scenarios skewHarvest(double putWidth, double callWidth, double putCredit, double callDebit) {
        double netCost = callDebit - putCredit;
        return new Scenarios(-(putWidth + netCost), -netCost, callWidth - netCost);
    }

    /** Skew harvest profit threshold: above this price the trade makes money. */
    public static double skewHarvestBreakeven(int putSellStrike, int callBuyStrike, double putCredit, double callDebit) {
        double netCost = callDebit - putCredit;
        // Net credit ⇒ breakeven sits inside the put spread; net debit ⇒ inside the call spread.
        return netCost <= 0 ? putSellStrike + netCost : callBuyStrike + netCost;
    }
}
