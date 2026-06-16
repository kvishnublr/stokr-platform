package com.stokr.strategy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/**
 * Binds a strategy catalog entry to a symbol universe group for runtime scanning.
 * Only bindings with runtimeEnabled=true are processed by CatalogDrivenScanScheduler.
 */
@Getter
@Setter
@Entity
@Table(name = "strategy_runtime_bindings")
public class StrategyRuntimeBinding {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "strategy_catalog_id", nullable = false)
    private StrategyDefinition strategyCatalog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "universe_group_id", nullable = false)
    private StrategyUniverseGroup universeGroup;

    /** Admin toggle: when false this binding is dormant and never scanned */
    @Column(name = "runtime_enabled", nullable = false)
    private boolean runtimeEnabled = false;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 5;

    @Column(name = "capital_limit", precision = 24, scale = 8)
    private BigDecimal capitalLimit;

    /** LOW | MEDIUM | HIGH */
    @Column(name = "risk_profile", nullable = false, length = 32)
    private String riskProfile = "MEDIUM";

    /** Per-binding override for scan frequency in seconds */
    @Column(name = "scan_interval_seconds", nullable = false)
    private int scanIntervalSeconds = 60;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
