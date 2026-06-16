package com.stokr.strategy.domain;

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
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "strategy_daily_pnl_snapshots")
public class StrategyDailyPnlSnapshot {

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

    @Column(name = "strategy_key", nullable = false, length = 128)
    private String strategyKey;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "realized_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "unrealized_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal unrealizedPnl = BigDecimal.ZERO;

    @Column(name = "trade_count", nullable = false)
    private int tradeCount = 0;

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
