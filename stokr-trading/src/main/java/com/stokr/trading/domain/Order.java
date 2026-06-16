package com.stokr.trading.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String side; // BUY, SELL

    @Column(name = "order_type", length = 20)
    @Builder.Default
    private String orderType = "MARKET"; // MARKET, LIMIT, SL

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 4)
    private BigDecimal price;

    @Column(name = "trigger_price", precision = 18, scale = 4)
    private BigDecimal triggerPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal filledQuantity;

    @Column(name = "average_price", precision = 18, scale = 4)
    private BigDecimal averagePrice;

    @Column(length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, SUBMITTED, PARTIAL, FILLED, CANCELLED, REJECTED

    @Column(name = "broker_order_id")
    private String brokerOrderId;

    @Column(name = "exchange_order_id")
    private String exchangeOrderId;

    @Column(name = "exchange")
    private String exchange; // NSE, BSE, MCX

    @Column(name = "product_type")
    @Builder.Default
    private String productType = "MIS"; // CNC, MIS, NRML

    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private String metadata = "{}";

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "filled_at")
    private Instant filledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    public boolean isBuy() {
        return "BUY".equalsIgnoreCase(side);
    }

    public boolean isSell() {
        return "SELL".equalsIgnoreCase(side);
    }

    public boolean isMarket() {
        return "MARKET".equalsIgnoreCase(orderType);
    }

    public boolean isLimit() {
        return "LIMIT".equalsIgnoreCase(orderType);
    }

    public boolean isPending() {
        return "PENDING".equalsIgnoreCase(status);
    }

    public boolean isFilled() {
        return "FILLED".equalsIgnoreCase(status);
    }

    public boolean isCancelled() {
        return "CANCELLED".equalsIgnoreCase(status);
    }

    public BigDecimal getOrderValue() {
        BigDecimal qty = filledQuantity != null ? filledQuantity : quantity;
        BigDecimal prc = averagePrice != null ? averagePrice : price;
        if (qty == null || prc == null) return BigDecimal.ZERO;
        return qty.multiply(prc);
    }
}
