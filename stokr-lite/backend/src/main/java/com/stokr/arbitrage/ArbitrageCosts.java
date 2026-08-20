package com.stokr.arbitrage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for box-spread / parity trade cost calculations.
 * Direction-aware STT, discount factor, and all exchange/SEBI charges.
 */
public final class ArbitrageCosts {

    private ArbitrageCosts() {}

    public static final double RISK_FREE_RATE = 0.065;
    public static final double STT_OPTION_SELL = 0.00125;
    public static final double STT_OPTION_BUY = 0.00025;
    private static final double STT_FUTURES = 0.00025;
    private static final double BROKERAGE = 120.0;
    /** Flat per-order brokerage estimate, derived from the 3-leg BROKERAGE convention above. */
    public static final double PER_LEG_BROKERAGE = BROKERAGE / 3.0;
    public static final double EXCHANGE_RATE = 0.0000345;
    public static final double SEBI_RATE = 0.000001;
    public static final double GST_RATE = 0.18;
    public static final double STAMP_RATE = 0.00005;

    /**
     * Result of a cost calculation, containing both the net edge and breakdown.
     */
    public record CostResult(double netEdge, Map<String, Double> breakdown) {}

    /**
     * Compute the discounted strike for parity: K * exp(-rT).
     * @param strike     ATM strike price
     * @param daysToExpiry  calendar days to expiry (0 for intraday/FUT)
     */
    public static double discountedStrike(int strike, double daysToExpiry) {
        if (daysToExpiry <= 0) return strike;
        double T = daysToExpiry / 365.0;
        return strike * Math.exp(-RISK_FREE_RATE * T);
    }

    /**
     * Compute total round-trip costs for a 3-leg box spread trade.
     *
     * @param cePrice   CE premium (mid or last)
     * @param pePrice   PE premium (mid or last)
     * @param futPrice  FUT price
     * @param lotSize   lot size for the underlying
     * @param grossEdge gross parity deviation in INR (parityDev * lotSize)
     * @param action    "BUY CE + SELL PE + SELL FUT" or "SELL CE + BUY PE + BUY FUT"
     * @return CostResult with netEdge and breakdown
     */
    public static CostResult calculate(double cePrice, double pePrice, double futPrice,
                                        int lotSize, double grossEdge, String action) {
        if (cePrice <= 0 || pePrice <= 0 || futPrice <= 0 || lotSize <= 0) {
            return new CostResult(0, Map.of());
        }

        double totalTurnover = (cePrice + pePrice + futPrice) * lotSize * 2;

        // Direction-aware STT
        double sttCe, sttPe, sttFut;
        boolean isConvergent = action != null && action.toUpperCase().contains("BUY CE +");
        if (isConvergent) {
            // BUY CE + SELL PE + SELL FUT
            sttCe = cePrice * lotSize * STT_OPTION_BUY;
            sttPe = pePrice * lotSize * STT_OPTION_SELL;
            sttFut = futPrice * lotSize * STT_FUTURES;
        } else {
            // SELL CE + BUY PE + BUY FUT
            sttCe = cePrice * lotSize * STT_OPTION_SELL;
            sttPe = pePrice * lotSize * STT_OPTION_BUY;
            sttFut = futPrice * lotSize * STT_FUTURES;
        }
        double stt = sttCe + sttPe + sttFut;

        double exchange = totalTurnover * EXCHANGE_RATE;
        double sebi = totalTurnover * SEBI_RATE;
        double gst = (BROKERAGE + exchange + sebi) * GST_RATE;
        double stamp = (cePrice + pePrice + futPrice) * lotSize * STAMP_RATE;
        double totalCosts = stt + BROKERAGE + exchange + sebi + gst + stamp;

        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("stt", round2(stt));
        breakdown.put("sttCe", round2(sttCe));
        breakdown.put("sttPe", round2(sttPe));
        breakdown.put("sttFut", round2(sttFut));
        breakdown.put("brokerage", BROKERAGE);
        breakdown.put("exchange", round2(exchange));
        breakdown.put("sebi", round2(sebi));
        breakdown.put("gst", round2(gst));
        breakdown.put("stamp", round2(stamp));
        breakdown.put("totalCosts", round2(totalCosts));
        breakdown.put("grossEdge", round2(grossEdge));
        breakdown.put("netEdge", round2(Math.max(0, grossEdge - totalCosts)));

        return new CostResult(Math.max(0, grossEdge - totalCosts), breakdown);
    }

    /**
     * Convenience overload without action — defaults to non-directional STT
     * (used for scanning where action isn't yet known).
     */
    public static CostResult calculate(double cePrice, double pePrice, double futPrice,
                                        int lotSize, double grossEdge) {
        return calculate(cePrice, pePrice, futPrice, lotSize, grossEdge, null);
    }

    /**
     * Compute net edge (grossEdge - costs) for signal generation.
     */
    public static double netEdge(double cePrice, double pePrice, double futPrice,
                                  int lotSize, double grossEdge, String action) {
        return calculate(cePrice, pePrice, futPrice, lotSize, grossEdge, action).netEdge();
    }

    /**
     * Convenience overload without action — uses non-directional STT for scanning.
     */
    public static double netEdge(double cePrice, double pePrice, double futPrice,
                                  int lotSize, double grossEdge) {
        return netEdge(cePrice, pePrice, futPrice, lotSize, grossEdge, null);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
