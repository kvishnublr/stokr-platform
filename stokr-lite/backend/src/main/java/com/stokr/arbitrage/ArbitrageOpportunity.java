package com.stokr.arbitrage;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a detected option mispricing opportunity.
 */
public class ArbitrageOpportunity {

    public Long id;
    public String type;           // PARITY_BREAK, IV_SPIKE, DEEP_ITM_STALE, SKEW_ANOMALY
    public String action;         // CONVERSION, REVERSAL, SELL_STRADDLE, BUY_DEEP_ITM, SELL_PUT_BUY_CALL
    public String underlying;     // NIFTY, BANKNIFTY
    public int strike;
    public String legs;           // Human-readable trade description
    public String description;    // Detailed explanation

    public double spotPrice;
    public double futuresPrice;
    public double cePrice;
    public double pePrice;
    public double ceBid, ceAsk;
    public double peBid, peAsk;

    public double edgePoints;     // Raw edge in points
    public double edgeAfterCosts; // Net edge after transaction costs (₹)
    public double daysToExpiry;
    public double confidence;     // 0-100

    // Cost breakdown (for parity break opportunities)
    public Map<String, Double> costBreakdown;

    public LocalDateTime detectedAt;

    public ArbitrageOpportunity() {
        this.detectedAt = LocalDateTime.now();
    }

    /**
     * Convert to map for API response
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type);
        map.put("action", action);
        map.put("underlying", underlying);
        map.put("strike", strike);
        map.put("legs", legs);
        map.put("description", description);
        map.put("spotPrice", spotPrice);
        map.put("futuresPrice", futuresPrice);
        map.put("cePrice", cePrice);
        map.put("pePrice", pePrice);
        map.put("ceBid", ceBid);
        map.put("ceAsk", ceAsk);
        map.put("peBid", peBid);
        map.put("peAsk", peAsk);
        map.put("edgePoints", Math.round(edgePoints * 100.0) / 100.0);
        map.put("edgeAfterCosts", Math.round(edgeAfterCosts * 100.0) / 100.0);
        map.put("daysToExpiry", daysToExpiry);
        map.put("confidence", Math.round(confidence));
        map.put("detectedAt", detectedAt.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime().atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toString());
        if (costBreakdown != null) {
            map.put("costBreakdown", costBreakdown);
        }
        return map;
    }
}
