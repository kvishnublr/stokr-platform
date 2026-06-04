package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Intraday trade record with setup type, entry/exit, and outcome
 * Used for tracking user performance and probability validation
 */
@Entity
@Table(name = "user_trades", indexes = {
        @Index(name = "idx_user_trades_user_entry", columnList = "user_id, entry_time"),
        @Index(name = "idx_user_trades_stock_entry", columnList = "stock_id, entry_time"),
        @Index(name = "idx_user_trades_setup_type", columnList = "setup_type, entry_time"),
        @Index(name = "idx_user_trades_status", columnList = "user_id, status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long tradeId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String stockId;

    @Column(nullable = false, length = 50)
    private String setupType; // gap_fill, vwap_bounce, sector_laggard, early_breakout

    // Entry Details
    @Column(nullable = false)
    private Instant entryTime;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal entryPrice;

    @Column(nullable = false)
    private Integer quantity;

    // Target & Stop Loss
    @Column(precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal stopLossPrice;

    // Exit Details
    private Instant exitTime;

    @Column(precision = 10, scale = 2)
    private BigDecimal exitPrice;

    // Status & Results
    @Column(length = 20)
    private String status; // OPEN, CLOSED, STOPPED_OUT

    @Column(precision = 12, scale = 2)
    private BigDecimal profitLoss;

    @Column(precision = 6, scale = 4)
    private BigDecimal profitLossPercent;

    @Column
    private Integer holdingTimeMinutes;

    @Column(length = 10)
    private String result; // WIN, LOSS, BREAK_EVEN

    // Context Information
    @Column(length = 20)
    private String marketRegimeAtEntry;

    @Column(precision = 5, scale = 2)
    private BigDecimal entrySetupQualityScore;

    @Column(precision = 5, scale = 4)
    private BigDecimal entryProbability;

    @Column(columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt;

    @Column(columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Mark trade as closed with final price
     */
    public void closePosition(BigDecimal exitPrice, Instant exitTime) {
        this.exitPrice = exitPrice;
        this.exitTime = exitTime;
        this.status = "CLOSED";

        // Calculate P&L
        BigDecimal totalCost = entryPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal totalRevenue = exitPrice.multiply(BigDecimal.valueOf(quantity));
        this.profitLoss = totalRevenue.subtract(totalCost);
        this.profitLossPercent = this.profitLoss.divide(totalCost, 4, java.math.RoundingMode.HALF_UP);

        // Determine result
        if (this.profitLoss.signum() > 0) {
            this.result = "WIN";
        } else if (this.profitLoss.signum() < 0) {
            this.result = "LOSS";
        } else {
            this.result = "BREAK_EVEN";
        }

        // Calculate holding time
        long minutes = java.time.temporal.ChronoUnit.MINUTES.between(entryTime, exitTime);
        this.holdingTimeMinutes = (int) minutes;
    }

    /**
     * Mark trade as stopped out (hit stop loss)
     */
    public void stopOut(Instant stopTime) {
        this.exitTime = stopTime;
        this.exitPrice = stopLossPrice;
        this.status = "STOPPED_OUT";
        this.result = "LOSS";

        BigDecimal totalCost = entryPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal totalRevenue = exitPrice.multiply(BigDecimal.valueOf(quantity));
        this.profitLoss = totalRevenue.subtract(totalCost);
        this.profitLossPercent = this.profitLoss.divide(totalCost, 4, java.math.RoundingMode.HALF_UP);

        long minutes = java.time.temporal.ChronoUnit.MINUTES.between(entryTime, stopTime);
        this.holdingTimeMinutes = (int) minutes;
    }

    public boolean isWin() {
        return "WIN".equals(result);
    }

    public boolean isLoss() {
        return "LOSS".equals(result);
    }

    public boolean isClosed() {
        return "CLOSED".equals(status) || "STOPPED_OUT".equals(status);
    }
}
