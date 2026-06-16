package com.stokr.oms;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side; // BUY, SELL

    @Column(nullable = false)
    private Integer quantity;

    private BigDecimal price;

    @Column(name = "order_type", nullable = false)
    @Builder.Default
    private String orderType = "MARKET";

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "broker_order_id")
    private String brokerOrderId;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Status constants
    public static final String PENDING = "PENDING";
    public static final String OPEN = "OPEN";
    public static final String COMPLETE = "COMPLETE";
    public static final String CANCELLED = "CANCELLED";
    public static final String REJECTED = "REJECTED";
}
