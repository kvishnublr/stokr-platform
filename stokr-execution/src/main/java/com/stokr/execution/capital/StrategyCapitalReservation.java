package com.stokr.execution.capital;

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
@Table(name = "strategy_capital_reservations")
public class StrategyCapitalReservation {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "strategy_key", nullable = false, length = 128)
    private String strategyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "reserved_amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal reservedAmount;

    @Column(name = "reserved_quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal reservedQuantity;

    @Column(name = "entry_price", precision = 24, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "release_reason", length = 256)
    private String releaseReason;

    @Column(name = "sizing_snapshot_hash", length = 64)
    private String sizingSnapshotHash;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        version++;
    }
}
