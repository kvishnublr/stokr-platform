package com.stokr.intraday.metrics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderFlowSignalEnhancement {

    private String symbol;
    private Instant timestamp;

    // Order Flow Metrics
    private BigDecimal bidAskRatio;
    private Integer buyerPressureScore;      // 0-100
    private Integer sellerPressureScore;     // 0-100
    private Integer liquidityScore;          // 0-100

    // Spread Analysis
    private BigDecimal spread;
    private BigDecimal spreadPct;

    // Volumes
    private Long bidVolume;
    private Long askVolume;
    private Long imbalance;                  // bid - ask

    // Recommendations
    private String recommendation;           // STRONG_BUY_PRESSURE, BUY_PRESSURE, NEUTRAL,
                                            // WEAK_SELL_PRESSURE, SELL_PRESSURE, POOR_LIQUIDITY_SKIP
    private Integer confidence;              // 0-100 (how much to trust order flow)
    private String pressureType;
    private Boolean isValid;

    // Technical Fields
    private Integer buyerInitiatedPct;       // % of buy-side volume
    private Integer sellerInitiatedPct;      // % of sell-side volume
    private Long cumulativeBidDepth;         // Total bid volume (10 levels)
    private Long cumulativeAskDepth;         // Total ask volume (10 levels)

    // Signals for Integration
    @Builder.Default
    private Boolean shouldEnhanceConfidence = false; // Recommendation: increase signal confidence
    @Builder.Default
    private Boolean shouldReduceConfidence = false;  // Recommendation: decrease signal confidence
    @Builder.Default
    private Boolean shouldSkip = false;              // Recommendation: skip signal entirely

    // Error Handling
    @Builder.Default
    private Boolean error = false;
    private String errorMessage;

    // Factory Methods
    public static OrderFlowSignalEnhancement noData(String symbol) {
        return OrderFlowSignalEnhancement.builder()
            .symbol(symbol)
            .error(true)
            .errorMessage("No order flow data available")
            .confidence(0)
            .build();
    }

    public static OrderFlowSignalEnhancement error(String symbol) {
        return OrderFlowSignalEnhancement.builder()
            .symbol(symbol)
            .error(true)
            .errorMessage("Error calculating order flow metrics")
            .confidence(0)
            .build();
    }

    // Helper methods
    public boolean isStrongBuySignal() {
        return !hasError() && scoreAbove(buyerPressureScore, 70) && scoreAbove(liquidityScore, 60);
    }

    public boolean isBuySignal() {
        return !hasError() && scoreAbove(buyerPressureScore, 55) && scoreAbove(liquidityScore, 50);
    }

    public boolean isStrongSellSignal() {
        return !hasError() && scoreAbove(sellerPressureScore, 70) && scoreAbove(liquidityScore, 60);
    }

    public boolean isSellSignal() {
        return !hasError() && scoreAbove(sellerPressureScore, 55) && scoreAbove(liquidityScore, 50);
    }

    public boolean isPoorLiquidity() {
        return !hasError() && (liquidityScore == null || liquidityScore < 40);
    }

    public boolean isNeutral() {
        return !hasError() &&
               between(buyerPressureScore, 45, 55) &&
               between(sellerPressureScore, 45, 55);
    }

    // Confidence multiplier for signal quality adjustment
    public double getConfidenceMultiplier() {
        if (hasError() || confidence == null) {
            return 1.0;  // No adjustment
        }

        // Convert 0-100 scale to multiplier:
        // 100 = 1.5x boost
        // 50 = 1.0x (no change)
        // 0 = 0.5x reduction
        return 1.0 + ((confidence / 100.0) - 0.5) * 0.5;
    }

    // Get signal strength rating
    public String getSignalStrength() {
        if (hasError()) return "ERROR";
        if (scoreAbove(buyerPressureScore, 80) || scoreAbove(sellerPressureScore, 80)) return "VERY_STRONG";
        if (scoreAbove(buyerPressureScore, 65) || scoreAbove(sellerPressureScore, 65)) return "STRONG";
        if (scoreAbove(buyerPressureScore, 55) || scoreAbove(sellerPressureScore, 55)) return "MODERATE";
        if (scoreAbove(buyerPressureScore, 45) || scoreAbove(sellerPressureScore, 45)) return "WEAK";
        return "NEUTRAL";
    }

    private boolean hasError() {
        return Boolean.TRUE.equals(error);
    }

    private static boolean scoreAbove(Integer score, int threshold) {
        return score != null && score > threshold;
    }

    private static boolean between(Integer score, int min, int max) {
        return score != null && score >= min && score <= max;
    }
}
