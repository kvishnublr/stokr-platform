package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Backtested win rates by setup type, market regime, time of day, and sector
 * Used to calculate base probabilities for newly detected setups
 * Updated via backtesting runs and validated against live results
 */
@Entity
@Table(name = "historical_win_rates", indexes = {
        @Index(name = "idx_historical_win_rates_lookup",
                columnList = "setup_type, market_regime, hour_of_day"),
        @Index(name = "idx_historical_win_rates_sector",
                columnList = "sector, setup_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoricalWinRate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long winRateId;

    @Column(nullable = false, length = 50)
    private String setupType; // gap_fill, vwap_bounce, sector_laggard, early_breakout

    // Gap Fill specific
    @Column(length = 10)
    private String gapDirection; // UP, DOWN

    @Column(precision = 5, scale = 2)
    private BigDecimal gapSizeMin;

    @Column(precision = 5, scale = 2)
    private BigDecimal gapSizeMax;

    // Market Regime
    @Column(length = 20)
    private String marketRegime; // TRENDING_UP, TRENDING_DOWN, CHOPPY, VOLATILE, QUIET

    // VWAP Bounce specific
    @Column(precision = 5, scale = 2)
    private BigDecimal volumeRatioMin;

    @Column(precision = 5, scale = 2)
    private BigDecimal volumeRatioMax;

    @Column
    private Integer touchesMin;

    @Column
    private Integer touchesMax;

    // Time of Day
    @Column
    private Integer hourOfDay; // 9, 10, 11, etc.

    // Sector
    @Column(length = 50)
    private String sector;

    // Results
    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer winCount;

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer lossCount;

    @Column(precision = 5, scale = 4)
    private BigDecimal winRate; // win_count / (win_count + loss_count)

    @Column(precision = 5, scale = 4)
    private BigDecimal avgWinPercent; // Average win size as % of entry

    @Column(precision = 5, scale = 4)
    private BigDecimal avgLossPercent; // Average loss size as % of entry

    @Column
    private Integer sampleSize; // Number of trades in this category

    @Column(length = 20)
    private String confidenceLevel; // HIGH (n>100), MEDIUM (n>50), LOW (n<50)

    @Column
    private Instant lastUpdated;

    @Column(columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        lastUpdated = Instant.now();
        updateConfidenceLevel();
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdated = Instant.now();
        updateConfidenceLevel();
    }

    /**
     * Calculate win rate from count data
     */
    public void calculateWinRate() {
        if (winCount == null || lossCount == null) {
            winCount = 0;
            lossCount = 0;
        }

        int total = winCount + lossCount;
        if (total == 0) {
            this.winRate = BigDecimal.ZERO;
        } else {
            this.winRate = BigDecimal.valueOf(winCount).divide(
                    BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP);
        }

        sampleSize = total;
        updateConfidenceLevel();
    }

    private void updateConfidenceLevel() {
        if (sampleSize == null || sampleSize == 0) {
            this.confidenceLevel = "LOW";
        } else if (sampleSize >= 100) {
            this.confidenceLevel = "HIGH";
        } else if (sampleSize >= 50) {
            this.confidenceLevel = "MEDIUM";
        } else {
            this.confidenceLevel = "LOW";
        }
    }

    /**
     * Record a trade result in this bucket
     */
    public void recordTrade(boolean won) {
        if (won) {
            winCount = winCount == null ? 1 : winCount + 1;
        } else {
            lossCount = lossCount == null ? 1 : lossCount + 1;
        }
        calculateWinRate();
    }

    /**
     * Calculate expected value for a setup
     * E[V] = prob * avg_win - (1 - prob) * abs(avg_loss)
     */
    public BigDecimal calculateExpectedValue() {
        if (winRate == null || avgWinPercent == null || avgLossPercent == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal winComponent = winRate.multiply(avgWinPercent);
        BigDecimal lossComponent = BigDecimal.ONE.subtract(winRate)
                .multiply(avgLossPercent.abs());

        return winComponent.subtract(lossComponent);
    }
}
