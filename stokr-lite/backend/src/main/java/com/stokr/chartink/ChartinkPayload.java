package com.stokr.chartink;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for Chartink Premium webhook payloads.
 * Chartink sends scanner hits via webhook with this structure.
 */
public record ChartinkPayload(
        String scannerName,
        String scanName,
        String symbol,
        String exchange,
        BigDecimal ltp,
        Long volume,
        Long buyerQty,
        Long sellerQty,
        BigDecimal changePct,
        BigDecimal gapPct,
        BigDecimal vwapDeviationPct,
        BigDecimal atr14,
        BigDecimal adx14,
        BigDecimal rvol,
        BigDecimal vwap,
        BigDecimal rsi14,
        BigDecimal unfilledRatio,
        BigDecimal vix,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal prevClose,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        Long bidQty,
        Long askQty,
        String niftyChangePct,
        String stockCategory,
        Instant timestamp,
        String triggerType
) {
    /**
     * Determines side from scanner name and price action.
     */
    public String inferSide() {
        String name = scannerName != null ? scannerName.toUpperCase() : "";
        if (name.contains("BREAKOUT") || name.contains("MOMENTUM") || name.contains("SPIKE")) {
            return changePct != null && changePct.compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "SELL";
        }
        if (name.contains("REVERS") || name.contains("FADE") || name.contains("GAP_FILL")) {
            return changePct != null && changePct.compareTo(BigDecimal.ZERO) > 0 ? "SELL" : "BUY";
        }
        if (gapPct != null) {
            return gapPct.compareTo(BigDecimal.ZERO) > 0 ? "SELL" : "BUY"; // Fade the gap
        }
        return "BUY";
    }
}
