package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Real-time setup detection result
 * Represents a trading opportunity that meets criteria for one of 4 setup types:
 * - Gap Fill: Stock gap fills from previous day
 * - VWAP Bounce: Price bounces off VWAP
 * - Sector Laggard: Stock lags sector momentum
 * - Early Breakout: Breakout within first hour (9:30-10:30 AM)
 */
@Entity
@Table(name = "current_setups", indexes = {
        @Index(name = "idx_current_setups_active", columnList = "is_active, time_detected"),
        @Index(name = "idx_current_setups_quality", columnList = "quality_score, time_detected"),
        @Index(name = "idx_current_setups_stock", columnList = "stock_id, is_active"),
        @Index(name = "idx_current_setups_expiry", columnList = "expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CurrentSetup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long setupId;

    @Column(nullable = false)
    private Instant timeDetected;

    @Column(nullable = false, length = 10)
    private String stockId;

    @Column(nullable = false, length = 50)
    private String setupType; // gap_fill, vwap_bounce, sector_laggard, early_breakout

    // Setup Entry/Exit Details
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal entryPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal stopLoss;

    @Column(precision = 10, scale = 2)
    private BigDecimal riskAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal rewardAmount;

    @Column(precision = 5, scale = 2)
    private BigDecimal riskRewardRatio; // Minimum 1.5 for entry

    // Probability Calculations
    @Column(precision = 5, scale = 4)
    private BigDecimal baseProbability; // From historical_win_rates

    @Column(length = 20)
    private String marketRegime; // TRENDING_UP, DOWN, CHOPPY, VOLATILE, QUIET

    @Column(precision = 5, scale = 2)
    private BigDecimal regimeAdjustment; // % adjustment (e.g., +15 for favorable regime)

    @Column(precision = 5, scale = 4)
    private BigDecimal adjustedProbability; // base * (1 + regime_adjustment)

    // Quality Metrics
    @Column(length = 20)
    private String confidenceLevel; // HIGH, MEDIUM, LOW

    @Column(precision = 10, scale = 4)
    private BigDecimal expectedValue; // E[V] = prob * avg_win - (1-prob) * avg_loss

    @Column(precision = 5, scale = 2)
    private BigDecimal qualityScore; // 0-100, used for ranking (composite of prob, RR, regime)

    @Column
    private Integer timeToExpiryMinutes; // Minutes until this setup is no longer valid

    // Status
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean isActive;

    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean isRecommendedForUser;

    @Column(columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt;

    @Column
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        isActive = isActive == null ? true : isActive;
        isRecommendedForUser = isRecommendedForUser == null ? false : isRecommendedForUser;
    }

    /**
     * Check if this setup is still valid (not expired)
     */
    public boolean isValid() {
        return isActive && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    /**
     * Calculate quality score (0-100) for ranking
     * Composite of: adjusted probability (40%), risk/reward (30%), confidence (20%), regime fit (10%)
     */
    public void calculateQualityScore() {
        if (adjustedProbability == null || riskRewardRatio == null) {
            this.qualityScore = BigDecimal.ZERO;
            return;
        }

        BigDecimal probScore = adjustedProbability.multiply(BigDecimal.valueOf(40)); // 40% weight
        BigDecimal rrScore = riskRewardRatio.divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(30)); // 30% weight, capped at 100
        BigDecimal confScore = getConfidenceScore().multiply(BigDecimal.valueOf(20)); // 20% weight
        BigDecimal regimeScore = BigDecimal.valueOf(10); // Assume favorable for now

        this.qualityScore = probScore.add(rrScore).add(confScore).add(regimeScore)
                .min(BigDecimal.valueOf(100));
    }

    private BigDecimal getConfidenceScore() {
        return switch (confidenceLevel) {
            case "HIGH" -> BigDecimal.valueOf(100);
            case "MEDIUM" -> BigDecimal.valueOf(70);
            case "LOW" -> BigDecimal.valueOf(40);
            default -> BigDecimal.valueOf(50);
        };
    }
}
