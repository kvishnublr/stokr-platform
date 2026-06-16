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

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-managed reusable symbol universe.
 * Supports EQUITY (NSE), COMMODITY (MCX), FUTURES (NFO), OPTIONS (NFO), CURRENCY (CDS).
 */
@Getter
@Setter
@Entity
@Table(name = "strategy_universe_groups")
public class StrategyUniverseGroup {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_key", nullable = false, unique = true, length = 128)
    private String groupKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "description", length = 500)
    private String description;

    /** INDEX_CONSTITUENTS | CUSTOM | SECTOR | FUTURES | OPTIONS | COMMODITY */
    @Column(name = "universe_type", nullable = false, length = 64)
    private String universeType = "INDEX_CONSTITUENTS";

    @Column(name = "exchange", nullable = false, length = 16)
    private String exchange = "NSE";

    /** EQUITY | COMMODITY | FUTURES | OPTIONS | CURRENCY */
    @Column(name = "asset_class", nullable = false, length = 32)
    private String assetClass = "EQUITY";

    /** NSE | NFO | MCX | CDS | BSE */
    @Column(name = "segment", nullable = false, length = 32)
    private String segment = "NSE";

    /** EQ | FUT | OPT | COM | CUR */
    @Column(name = "instrument_type", nullable = false, length = 32)
    private String instrumentType = "EQ";

    /** True when symbols are auto-synced by UniverseSyncService (e.g. NIFTY_50 constituents) */
    @Column(name = "auto_managed", nullable = false)
    private boolean autoManaged = false;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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
