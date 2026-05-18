package com.stokr.execution.guard;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "execution_guard_policy_override")
public class ExecutionGuardPolicyOverride extends BaseEntity {
    @ManyToOne
    @JoinColumn(name = "registry_id")
    private ExecutionGuardPolicyRegistry registry;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private ExecutionGuardScopeType scopeType;

    @Column(name = "scope_key", length = 128)
    private String scopeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "guard_mode", length = 32)
    private ExecutionGuardMode guardMode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "max_signal_age_ms")
    private Long maxSignalAgeMs;
    @Column(name = "max_drift_pct", precision = 12, scale = 6)
    private BigDecimal maxDriftPct;
    @Column(name = "max_spread_pct", precision = 12, scale = 6)
    private BigDecimal maxSpreadPct;
    @Column(name = "max_slippage_pct", precision = 12, scale = 6)
    private BigDecimal maxSlippagePct;
    @Column(name = "soft_stale_feed_ms")
    private Long softStaleFeedMs;
    @Column(name = "hard_stale_feed_ms")
    private Long hardStaleFeedMs;
    @Column(name = "volatility_threshold_pct", precision = 12, scale = 6)
    private BigDecimal volatilityThresholdPct;
    @Column(name = "min_liquidity_qty", precision = 24, scale = 8)
    private BigDecimal minLiquidityQty;
    @Column(name = "allow_exit_during_stale_feed")
    private Boolean allowExitDuringStaleFeed;
    @Column(name = "allow_soft_fail_execution")
    private Boolean allowSoftFailExecution;
    @Column(name = "revision", nullable = false)
    private Long revision = 1L;
}

