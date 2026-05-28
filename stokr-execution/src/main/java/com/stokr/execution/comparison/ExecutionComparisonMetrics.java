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

    @Column(name = "paper_exit_price", precision = 24, scale = 8)
    private BigDecimal paperExitPrice;

    @Column(name = "live_exit_price", precision = 24, scale = 8)
    private BigDecimal liveExitPrice;

    @Column(name = "slippage_entry", precision = 12, scale = 6)
    private BigDecimal slippageEntry;

    @Column(name = "slippage_exit", precision = 12, scale = 6)
    private BigDecimal slippageExit;

    @Column(name = "hold_time_diff_seconds")
    private Long holdTimeDiffSeconds;

    @Column(name = "paper_exit_reason", length = 128)
    private String paperExitReason;

    @Column(name = "live_exit_reason", length = 128)
    private String liveExitReason;

    @Column(name = "direction", length = 8)
    private String direction;

    @Column(name = "paper_realized_pnl", precision = 24, scale = 8)
    private BigDecimal paperRealizedPnl;

    @Column(name = "live_realized_pnl", precision = 24, scale = 8)
    private BigDecimal liveRealizedPnl;

    @Column(name = "pnl_drift", precision = 24, scale = 8)
    private BigDecimal pnlDrift;

    @Column(name = "paper_hold_seconds")
    private Long paperHoldSeconds;

    @Column(name = "live_hold_seconds")
    private Long liveHoldSeconds;

    @Column(name = "hold_time_drift")
    private Long holdTimeDrift;

    @Column(name = "paper_exit_category", length = 32)
    private String paperExitCategory;

    @Column(name = "live_exit_category", length = 32)
    private String liveExitCategory;

    @Column(name = "paper_max_drawdown", precision = 24, scale = 8)
    private BigDecimal paperMaxDrawdown;

    @Column(name = "live_max_drawdown", precision = 24, scale = 8)
    private BigDecimal liveMaxDrawdown;

    @Column(name = "paper_max_profit", precision = 24, scale = 8)
    private BigDecimal paperMaxProfit;

    @Column(name = "live_max_profit", precision = 24, scale = 8)
    private BigDecimal liveMaxProfit;

    @Column(name = "fill_count_difference")
    private Long fillCountDifference;

    @Column(name = "partial_fill_difference")
    private Long partialFillDifference;

    @Column(name = "paper_fill_count")
    private long paperFillCount;

    @Column(name = "live_fill_count")
    private long liveFillCount;

    @Column(name = "reconciliation_status", length = 32)
    private String reconciliationStatus = "PENDING";

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "paper_closed_at")
    private Instant paperClosedAt;

    @Column(name = "live_closed_at")
    private Instant liveClosedAt;

    @Column(name = "reconciliation_failure_reason", length = 512)
    private String reconciliationFailureReason;

    @Column(name = "paper_entry_filled")
    private boolean paperEntryFilled;

    @Column(name = "live_entry_filled")
    private boolean liveEntryFilled;

    @Column(name = "paper_position_closed")
    private boolean paperPositionClosed;

    @Column(name = "live_position_closed")
    private boolean livePositionClosed;

    @Column(name = "paper_entry_at")
    private Instant paperEntryAt;

    @Column(name = "live_entry_at")
    private Instant liveEntryAt;

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
