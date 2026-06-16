package com.stokr.trading.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(length = 10)
    private String side; // LONG, SHORT

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "avg_price", precision = 18, scale = 4)
    private BigDecimal avgPrice;

    @Column(name = "current_price", precision = 18, scale = 4)
    private BigDecimal currentPrice;

    @Column(precision = 18, scale = 2)
    private BigDecimal pnl;

    @Column(name = "unrealized_pnl", precision = 18, scale = 2)
    private BigDecimal unrealizedPnl;

    @Column(name = "realized_pnl", precision = 18, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "product_type")
    private String productType;

    @Column(length = 20)
    @Builder.Default
    private String status = "OPEN"; // OPEN, CLOSED

    @Column(name = "opened_at")
    @Builder.Default
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isLong() {
        return "LONG".equalsIgnoreCase(side);
    }

    public boolean isShort() {
        return "SHORT".equalsIgnoreCase(side);
    }

    public boolean isOpen() {
        return "OPEN".equalsIgnoreCase(status);
    }

    public boolean isClosed() {
        return "CLOSED".equalsIgnoreCase(status);
    }

    public BigDecimal calculateUnrealizedPnl() {
        if (currentPrice == null || avgPrice == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal priceDiff = currentPrice.subtract(avgPrice);
        if ("SHORT".equalsIgnoreCase(side)) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(quantity.abs());
    }

    public void updatePnl() {
        this.unrealizedPnl = calculateUnrealizedPnl();
    }

    public BigDecimal getPositionValue() {
        if (quantity == null || avgPrice == null) return BigDecimal.ZERO;
        return quantity.abs().multiply(avgPrice);
    }
}
