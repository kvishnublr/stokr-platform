package com.stokr.strategy.validation;

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
@Table(name = "strategy_validation_metrics")
public class StrategyValidationMetrics {

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

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "validation_status", nullable = false, length = 32)
    private String validationStatus;

    @Column(name = "signals_generated", nullable = false)
    private long signalsGenerated;

    @Column(name = "targets_hit", nullable = false)
    private long targetsHit;

    @Column(name = "stop_losses_hit", nullable = false)
    private long stopLossesHit;

    @Column(name = "pressure_exits", nullable = false)
    private long pressureExits;

    @Column(name = "avg_hold_minutes", precision = 12, scale = 4)
    private BigDecimal avgHoldMinutes;

    @Column(name = "expectancy", precision = 12, scale = 6)
    private BigDecimal expectancy;

    @Column(name = "avg_r_multiple", precision = 12, scale = 6)
    private BigDecimal avgRMultiple;

    @Column(name = "max_drawdown", precision = 24, scale = 8)
    private BigDecimal maxDrawdown;

    @Column(name = "paper_pnl", precision = 24, scale = 8)
    private BigDecimal paperPnl;

    @Column(name = "live_pnl", precision = 24, scale = 8)
    private BigDecimal livePnl;

    @Column(name = "paper_live_drift", precision = 12, scale = 6)
    private BigDecimal paperLiveDrift;

    @Column(name = "sample_size", nullable = false)
    private long sampleSize;

    @Column(name = "win_rate", precision = 8, scale = 4)
    private BigDecimal winRate;

    @Column(name = "stale_signal_rejections", nullable = false)
    private long staleSignalRejections;

    @Column(name = "oms_reject_rate", precision = 8, scale = 4)
    private BigDecimal omsRejectRate;

    @Column(name = "sizing_rejections", nullable = false)
    private long sizingRejections;

    @Column(name = "fills", nullable = false)
    private long fills;

    @Column(name = "avg_slippage_bps", precision = 12, scale = 6)
    private BigDecimal avgSlippageBps;

    @Column(name = "win_rate_delta", precision = 8, scale = 4)
    private BigDecimal winRateDelta;

    @Column(name = "paper_win_rate", precision = 8, scale = 4)
    private BigDecimal paperWinRate;

    @Column(name = "live_win_rate", precision = 8, scale = 4)
    private BigDecimal liveWinRate;

    @Column(name = "integrity_rejection_pct", precision = 8, scale = 4)
    private BigDecimal integrityRejectionPct;

    @Column(name = "avg_execution_latency_ms")
    private Long avgExecutionLatencyMs;

    @Column(name = "strategy_degradation_score", precision = 12, scale = 6)
    private BigDecimal strategyDegradationScore;

    @Column(name = "live_underperformance_pct", precision = 12, scale = 6)
    private BigDecimal liveUnderperformancePct;

    @Column(name = "expectancy_drift", precision = 12, scale = 6)
    private BigDecimal expectancyDrift;

    @Column(name = "exit_timing_drift_seconds")
    private Long exitTimingDriftSeconds;

    @Column(name = "slippage_p50_bps", precision = 12, scale = 6)
    private BigDecimal slippageP50Bps;

    @Column(name = "slippage_p95_bps", precision = 12, scale = 6)
    private BigDecimal slippageP95Bps;

    @Column(name = "latency_p50_ms")
    private Long latencyP50Ms;

    @Column(name = "latency_p95_ms")
    private Long latencyP95Ms;

    @Column(name = "unreconciled_trades", nullable = false)
    private long unreconciledTrades;

    @Column(name = "reconciliation_failures", nullable = false)
    private long reconciliationFailures;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
