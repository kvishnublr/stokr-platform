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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-strategy execution configuration. Central authority for position sizing mode,
 * capital allocation, risk gate thresholds, and live/paper routing toggles.
 *
 * Production-safe defaults: force_fixed_qty=true, fixed_qty=1, max_positions=2,
 * live_enabled=false, execution_mode=PAPER.
 */
@Getter
@Setter
@Entity
@Table(name = "strategy_execution_configs")
public class StrategyExecutionConfig {

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

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id")
    private StrategyDefinition strategy;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "strategy_key", nullable = false, length = 128)
    private String strategyKey;

    // ── Execution routing ───────────────────────────────────────────────────

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** PAPER | LIVE | BOTH */
    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode = "PAPER";

    @Column(name = "live_enabled", nullable = false)
    private boolean liveEnabled = false;

    @Column(name = "paper_enabled", nullable = false)
    private boolean paperEnabled = true;

    @Column(name = "telegram_enabled", nullable = false)
    private boolean telegramEnabled = true;

    // ── Capital & position sizing ───────────────────────────────────────────

    @Column(name = "allocated_capital", precision = 24, scale = 8)
    private BigDecimal allocatedCapital;

    /** Max concurrent open positions for this strategy. */
    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 2;

    @Column(name = "max_trade_quantity", precision = 24, scale = 8)
    private BigDecimal maxTradeQuantity;

    /**
     * When true: always use fixedQty regardless of allocatedCapital.
     * Default TRUE — safe for initial live rollout.
     */
    @Column(name = "force_fixed_qty", nullable = false)
    private boolean forceFixedQty = true;

    /** Quantity used when forceFixedQty=true. Default 1. */
    @Column(name = "fixed_qty", nullable = false, precision = 24, scale = 8)
    private BigDecimal fixedQty = BigDecimal.ONE;

    // ── Risk gate thresholds ────────────────────────────────────────────────

    /** Max allowed daily loss (absolute). Null = disabled. */
    @Column(name = "daily_loss_limit", precision = 24, scale = 8)
    private BigDecimal dailyLossLimit;

    /** Minimum minutes between consecutive entries for this strategy. 0 = no cooldown. */
    @Column(name = "cooldown_minutes", nullable = false)
    private int cooldownMinutes = 0;

    @Column(name = "allow_pyramiding", nullable = false)
    private boolean allowPyramiding = false;

    /** When true: StrategyEmergencyStopRule blocks all new entries. */
    @Column(name = "emergency_stop_enabled", nullable = false)
    private boolean emergencyStopEnabled = false;

    /** When true: strategy is auto-disabled when daily_loss_limit is breached. */
    @Column(name = "auto_disable_on_loss", nullable = false)
    private boolean autoDisableOnLoss = false;

    @Column(name = "live_confirmation_required", nullable = false)
    private boolean liveConfirmationRequired = false;

    /** FIXED_QUANTITY | FIXED_CAPITAL_PER_TRADE | CAPITAL_BUCKET | RISK_BASED */
    @Column(name = "sizing_mode", nullable = false, length = 32)
    private String sizingMode = "FIXED_QUANTITY";

    @Column(name = "max_capital_per_trade", precision = 24, scale = 8)
    private BigDecimal maxCapitalPerTrade;

    @Column(name = "max_risk_per_trade_pct", precision = 8, scale = 4)
    private BigDecimal maxRiskPerTradePct;

    @Column(name = "max_total_exposure", precision = 24, scale = 8)
    private BigDecimal maxTotalExposure;

    @Column(name = "allow_fractional_capital_usage", nullable = false)
    private boolean allowFractionalCapitalUsage = false;

    /** FULLY_ALLOCATED | DYNAMIC_UTILIZATION | RESERVED_BUFFER */
    @Column(name = "capital_utilization_mode", nullable = false, length = 32)
    private String capitalUtilizationMode = "FULLY_ALLOCATED";

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
