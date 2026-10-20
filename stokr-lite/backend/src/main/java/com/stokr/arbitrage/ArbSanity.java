package com.stokr.arbitrage;

/**
 * Real NIFTY/BANKNIFTY box and put-call-parity mispricings are a point or two at most.
 * The 20-Oct-2026 audit found every paper Box (median 26 pts) and Bid Parity (25-80 pts)
 * "edge" came from leg quotes taken at different moments / stale cache, so anything
 * above these caps is treated as a data error, never as an opportunity.
 */
public final class ArbSanity {
    public static final double MAX_BOX_EDGE_PTS = 3.0;
    public static final double MAX_PARITY_EDGE_PTS = 5.0;

    private ArbSanity() {}

    /** True if a Box / Bid Parity edge (in index points) is too large to be real. */
    public static boolean implausibleEdge(String strategyType, double edgePoints) {
        String s = strategyType == null ? "" : strategyType.toUpperCase();
        if (s.contains("BOX")) return Math.abs(edgePoints) > MAX_BOX_EDGE_PTS;
        if (s.contains("PARITY")) return Math.abs(edgePoints) > MAX_PARITY_EDGE_PTS;
        return false;
    }

    /** Box and Bid Parity are excluded from all automatic entries (paper and live). */
    public static boolean autoTradeBlocked(String strategyType) {
        String s = strategyType == null ? "" : strategyType.toUpperCase();
        return s.contains("BOX") || s.contains("PARITY");
    }
}
