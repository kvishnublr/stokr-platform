package com.stokr.intraday.metrics.domain;

import lombok.*;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "intelligence_scores", indexes = {
    @Index(name = "idx_intelligence_symbol", columnList = "symbol"),
    @Index(name = "idx_intelligence_confidence", columnList = "confidence"),
    @Index(name = "idx_intelligence_recommendation", columnList = "recommendation"),
    @Index(name = "idx_intelligence_updated", columnList = "last_updated")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntelligenceScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "confidence")
    private Integer confidence;

    @Column(name = "recommendation", length = 30)
    private String recommendation;

    @Column(name = "buyer_pressure_score")
    private Integer buyerPressureScore;

    @Column(name = "seller_pressure_score")
    private Integer sellerPressureScore;

    @Column(name = "liquidity_score")
    private Integer liquidityScore;

    @Column(name = "spread_score")
    private Integer spreadScore;

    @Column(name = "confidence_multiplier")
    private Double confidenceMultiplier;

    @Column(name = "signal_strength", length = 30)
    private String signalStrength;

    @Builder.Default
    @Column(name = "should_enhance_confidence")
    private Boolean shouldEnhanceConfidence = false;

    @Builder.Default
    @Column(name = "should_reduce_confidence")
    private Boolean shouldReduceConfidence = false;

    @Builder.Default
    @Column(name = "should_skip")
    private Boolean shouldSkip = false;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        lastUpdated = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdated = Instant.now();
    }

    public boolean isStrongBuySignal() {
        return "STRONG_BUY_PRESSURE".equals(recommendation);
    }

    public boolean isBuySignal() {
        return "BUY_PRESSURE".equals(recommendation);
    }

    public boolean isSellSignal() {
        return "SELL_PRESSURE".equals(recommendation);
    }

    public boolean isNeutral() {
        return "NEUTRAL".equals(recommendation);
    }

    public boolean isPoorLiquidity() {
        return "POOR_LIQUIDITY_SKIP".equals(recommendation);
    }

    public boolean shouldProcess() {
        return !shouldSkip && confidence != null && confidence > 30;
    }
}
