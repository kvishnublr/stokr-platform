package com.stokr.intraday.metrics.domain;

import lombok.*;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "confidence_scores", indexes = {
    @Index(name = "idx_confidence_symbol_time", columnList = "symbol,timestamp"),
    @Index(name = "idx_confidence_score", columnList = "confidence_score"),
    @Index(name = "idx_confidence_timestamp", columnList = "timestamp"),
    @Index(name = "idx_confidence_symbol_recent", columnList = "symbol")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfidenceScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "confidence_score")
    private Integer confidenceScore;  // 0-100

    @Column(name = "buyer_pressure")
    private Integer buyerPressure;  // 0-100

    @Column(name = "seller_pressure")
    private Integer sellerPressure;  // 0-100

    @Column(name = "liquidity_score")
    private Integer liquidityScore;  // 0-100

    @Column(name = "signal_strength", length = 30)
    private String signalStrength;  // VERY_STRONG, STRONG, MODERATE, WEAK, NEUTRAL

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isAboveThreshold(int threshold) {
        return confidenceScore != null && confidenceScore > threshold;
    }

    public boolean isHighConfidence() {
        return confidenceScore != null && confidenceScore >= 80;
    }

    public boolean isVeryHighConfidence() {
        return confidenceScore != null && confidenceScore >= 80;  // Lowered from 90 to 80 for real data deployment
    }

    @Override
    public String toString() {
        return String.format("%s: %d%% (buyer:%d seller:%d liquidity:%d)",
            symbol, confidenceScore, buyerPressure, sellerPressure, liquidityScore);
    }
}
