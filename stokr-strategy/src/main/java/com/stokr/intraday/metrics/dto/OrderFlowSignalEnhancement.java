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
    private Boolean shouldEnhanceConfidence; // Recommendation: increase signal confidence
    private Boolean shouldReduceConfidence;  // Recommendation: decrease signal confidence
    private Boolean shouldSkip;              // Recommendation: skip signal entirely

    // Error Handling
    private Boolean error;
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
        return !error && buyerPressureScore > 70 && liquidityScore > 60;
    }

    public boolean isBuySignal() {
        return !error && buyerPressureScore > 55 && liquidityScore > 50;
    }

    public boolean isStrongSellSignal() {
        return !error && sellerPressureScore > 70 && liquidityScore > 60;
    }

    public boolean isSellSignal() {
        return !error && sellerPressureScore > 55 && liquidityScore > 50;
    }

    public boolean isPoorLiquidity() {
        return !error && (liquidityScore == null || liquidityScore < 40);
    }

    public boolean isNeutral() {
        return !error &&
               buyerPressureScore >= 45 && buyerPressureScore <= 55 &&
               sellerPressureScore >= 45 && sellerPressureScore <= 55;
    }

    // Confidence multiplier for signal quality adjustment
    public double getConfidenceMultiplier() {
        if (error || confidence == null) {
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
        if (error) return "ERROR";
        if (buyerPressureScore > 80 || sellerPressureScore > 80) return "VERY_STRONG";
        if (buyerPressureScore > 65 || sellerPressureScore > 65) return "STRONG";
        if (buyerPressureScore > 55 || sellerPressureScore > 55) return "MODERATE";
        if (buyerPressureScore > 45 || sellerPressureScore > 45) return "WEAK";
        return "NEUTRAL";
    }
}
