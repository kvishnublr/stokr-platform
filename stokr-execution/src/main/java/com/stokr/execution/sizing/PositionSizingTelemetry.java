package com.stokr.execution.sizing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "strategy_position_sizing_telemetry")
public class PositionSizingTelemetry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "sizing_mode", length = 32)
    private String sizingMode;

    @Column(name = "capital_allocated", precision = 24, scale = 8)
    private BigDecimal capitalAllocated;

    @Column(name = "capital_used", precision = 24, scale = 8)
    private BigDecimal capitalUsed;

    @Column(name = "reserved_capital", precision = 24, scale = 8)
    private BigDecimal reservedCapital;

    @Column(name = "quantity", precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "normalized_quantity", precision = 24, scale = 8)
    private BigDecimal normalizedQuantity;

    @Column(name = "entry_price", precision = 24, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "exposure_value", precision = 24, scale = 8)
    private BigDecimal exposureValue;

    @Column(name = "available_capital_before", precision = 24, scale = 8)
    private BigDecimal availableCapitalBefore;

    @Column(name = "available_capital_after", precision = 24, scale = 8)
    private BigDecimal availableCapitalAfter;

    @Column(name = "utilization_pct", precision = 8, scale = 4)
    private BigDecimal utilizationPct;

    @Column(name = "rejected", nullable = false)
    private boolean rejected;

    @Column(name = "rejected_reason", length = 512)
    private String rejectedReason;

    @Column(name = "sizing_snapshot_hash", length = 64)
    private String sizingSnapshotHash;

    @Column(name = "execution_mode", length = 16)
    private String executionMode;

    @Column(name = "broker_normalization_note", length = 256)
    private String brokerNormalizationNote;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
