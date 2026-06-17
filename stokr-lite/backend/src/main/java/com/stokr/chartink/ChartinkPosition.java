package com.stokr.chartink;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tracks open positions created from Chartink webhook signals.
 * Separate from the main Position entity to keep Chartink flow independent.
 */
@Entity
@Table(name = "chartink_positions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ChartinkPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_id", nullable = false, unique = true)
    private Long signalId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String side;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    @Column(name = "entry_price", precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "avg_price", precision = 15, scale = 4)
    private BigDecimal avgPrice;

    @Column(name = "stop_loss", precision = 15, scale = 4)
    private BigDecimal stopLoss;

    @Column(precision = 15, scale = 4)
    private BigDecimal target;

    @Column(name = "trailing_stop", precision = 15, scale = 4)
    private BigDecimal trailingStop;

    @Column(name = "highest_price", precision = 15, scale = 4)
    private BigDecimal highestPrice;

    @Column(name = "lowest_price", precision = 15, scale = 4)
    private BigDecimal lowestPrice;

    @Column(name = "unrealized_pnl", precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal unrealizedPnl = BigDecimal.ZERO;

    @Column(name = "realized_pnl", precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN"; // OPEN, CLOSED

    @Column(name = "exit_reason", length = 100)
    private String exitReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public void updateHighestLowest(BigDecimal ltp) {
        if (highestPrice == null || ltp.compareTo(highestPrice) > 0) {
            highestPrice = ltp;
        }
        if (lowestPrice == null || ltp.compareTo(lowestPrice) < 0) {
            lowestPrice = ltp;
        }
    }
}
