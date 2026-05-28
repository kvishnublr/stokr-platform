package com.stokr.execution.comparison;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "execution_comparison_metrics")
public class ExecutionComparisonMetrics {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "version", nullable = false)
    private long version = 0;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "signal_id")
    private UUID signalId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "live_order_id")
    private UUID liveOrderId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "paper_order_id")
    private UUID paperOrderId;

    @Column(name = "strategy_key", length = 128)
    private String strategyKey;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "live_fill_price", precision = 24, scale = 8)
    private BigDecimal liveFillPrice;

    @Column(name = "paper_fill_price", precision = 24, scale = 8)
    private BigDecimal paperFillPrice;

    @Column(name = "slippage_divergence_pct", precision = 12, scale = 6)
    private BigDecimal slippageDivergencePct;

    @Column(name = "live_latency_ms")
    private Long liveLatencyMs;

    @Column(name = "paper_latency_ms")
    private Long paperLatencyMs;

    @Column(name = "pnl_divergence", precision = 24, scale = 8)
    private BigDecimal pnlDivergence;

    @Column(name = "live_quantity", precision = 24, scale = 8)
    private BigDecimal liveQuantity;

    @Column(name = "paper_quantity", precision = 24, scale = 8)
    private BigDecimal paperQuantity;

    @Column(name = "quantity_drift", precision = 24, scale = 8)
    private BigDecimal quantityDrift;

    @Column(name = "paper_entry_price", precision = 24, scale = 8)
    private BigDecimal paperEntryPrice;

    @Column(name = "live_entry_price", precision = 24, scale = 8)
    private BigDecimal liveEntryPrice;

    @Column(name = "paper_pnl", precision = 24, scale = 8)
    private BigDecimal paperPnl;

    @Column(name = "live_pnl", precision = 24, scale = 8)
    private BigDecimal livePnl;

    @Column(name = "broker_ack_latency_ms")
    private Long brokerAckLatencyMs;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        version++;
    }
}
