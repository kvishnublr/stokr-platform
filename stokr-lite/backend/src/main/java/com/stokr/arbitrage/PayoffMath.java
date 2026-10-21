package com.stokr.arbitrage;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Expiry payoff of a set of same-expiry option legs, from the legs themselves rather than a
 * scanner's own "maxLoss" field. Payoff is piecewise linear with kinks only at the strikes, so
 * its minimum is at S = 0, at a strike, or unbounded above the top strike.
 */
public final class PayoffMath {
    private PayoffMath() {}

    /**
     * Worst loss per unit (one share of one lot) at expiry, >= 0; or null when it can't be
     * bounded: unlimited upside loss, a futures leg, mixed expiries, or missing strike/price.
     */
    public static Double maxLossPerUnit(List<Map<String, Object>> legs) {
        if (legs == null || legs.isEmpty()) return null;
        String expiry = null;
        TreeSet<Double> points = new TreeSet<>();
        points.add(0.0);
        double callSlope = 0;
        for (Map<String, Object> l : legs) {
            String type = l.get("optionType") instanceof String t ? t.toUpperCase() : null;
            if (!"CE".equals(type) && !"PE".equals(type)) return null;
            if (!(l.get("strike") instanceof Number k) || k.doubleValue() <= 0) return null;
            if (price(l) == null) return null;
            Object e = l.get("expiry");
            if (e != null) {
                if (expiry != null && !expiry.equals(e.toString())) return null;
                expiry = e.toString();
            }
            points.add(k.doubleValue());
            if ("CE".equals(type)) callSlope += sign(l) * qty(l);
        }
        if (callSlope < 0) return null;                       // net short calls: loss grows without bound
        double worst = Double.POSITIVE_INFINITY;
        for (double s : points) worst = Math.min(worst, pnlAt(legs, s));
        return Math.max(0, -worst);
    }

    /** P&L per unit at expiry with the underlying at {@code s}. */
    static double pnlAt(List<Map<String, Object>> legs, double s) {
        double pnl = 0;
        for (Map<String, Object> l : legs) {
            double k = ((Number) l.get("strike")).doubleValue();
            double value = "CE".equalsIgnoreCase(String.valueOf(l.get("optionType"))) ? Math.max(0, s - k) : Math.max(0, k - s);
            pnl += sign(l) * qty(l) * (value - price(l));
        }
        return pnl;
    }

    private static Double price(Map<String, Object> l) {
        Object p = l.get("entryPrice") instanceof Number ? l.get("entryPrice") : l.get("price");
        return p instanceof Number n && n.doubleValue() >= 0 ? n.doubleValue() : null;
    }

    private static int sign(Map<String, Object> l) { return "BUY".equalsIgnoreCase(String.valueOf(l.get("side"))) ? 1 : -1; }

    private static int qty(Map<String, Object> l) { return l.get("qty") instanceof Number n && n.intValue() > 0 ? n.intValue() : 1; }
}
