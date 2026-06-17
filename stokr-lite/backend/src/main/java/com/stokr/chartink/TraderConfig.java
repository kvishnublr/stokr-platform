package com.stokr.chartink;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-trader configuration for Chartink auto-trading.
 * Each trader controls their own capital, position limits, and risk parameters.
 */
@Entity
@Table(name = "trader_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** Trading mode: PAPER or LIVE */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    @Builder.Default
    private Mode mode = Mode.PAPER;

    /** Total capital allocated for auto-trading (default ₹15,000) */
    @Column(name = "capital", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal capital = new BigDecimal("15000");

    /** Maximum simultaneous open positions (default 3) */
    @Column(name = "max_positions")
    @Builder.Default
    private int maxPositions = 3;

    /** Minimum share price to trade (default ₹200) */
    @Column(name = "min_share_price", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal minSharePrice = new BigDecimal("200");

    /** Maximum share price to trade (default ₹3,000) */
    @Column(name = "max_share_price", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal maxSharePrice = new BigDecimal("3000");

    /** Stop loss percentage per trade (default 0.2%) */
    @Column(name = "stop_loss_pct", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal stopLossPct = new BigDecimal("0.2");

    /** Target percentage per trade (default 0.6% = 3:1 R:R) */
    @Column(name = "target_pct", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal targetPct = new BigDecimal("0.6");

    /** Max daily loss limit (default ₹225 = 1.5% of ₹15K) */
    @Column(name = "max_daily_loss", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal maxDailyLoss = new BigDecimal("225");

    /** Minimum gap between trades in minutes (default 2) */
    @Column(name = "min_trade_gap_minutes")
    @Builder.Default
    private int minTradeGapMinutes = 2;

    /** Max consecutive losses before strategy pause (default 3) */
    @Column(name = "max_consecutive_losses")
    @Builder.Default
    private int maxConsecutiveLosses = 3;

    /** Whether auto-trading is enabled for this trader */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public enum Mode {
        PAPER, LIVE
    }

    /**
     * Calculate per-trade capital based on total capital and max positions.
     */
    public BigDecimal getPerTradeCapital() {
        if (maxPositions <= 0) return capital;
        return capital.divide(BigDecimal.valueOf(maxPositions), 2, java.math.RoundingMode.DOWN);
    }

    /**
     * Calculate quantity for a given stock price.
     * Returns 0 if price is outside min/max share price range.
     */
    public int calculateQuantity(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) return 0;
        if (price.compareTo(minSharePrice) < 0 || price.compareTo(maxSharePrice) > 0) return 0;

        BigDecimal perTrade = getPerTradeCapital();
        return perTrade.divide(price, 0, java.math.RoundingMode.DOWN).intValue();
    }

    public static TraderConfig defaultsFor(Long userId) {
        return TraderConfig.builder().userId(userId).build();
    }
}
