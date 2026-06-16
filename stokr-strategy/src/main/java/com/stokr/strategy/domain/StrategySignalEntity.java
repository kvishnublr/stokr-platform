package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.PrePersist;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "strategy_signals")
public class StrategySignalEntity extends BaseEntity {

    public static final String STRATEGY_KEY = "NSE_SPIKE_DETECTION";
    public static final String VERSION = "1.0.0";

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "instance_id")
    private StrategyInstance instance;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 16)
    private SignalType signalType;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "suggested_qty", precision = 24, scale = 8)
    private BigDecimal suggestedQty;

    @Column(name = "strategy_name", length = 128)
    private String strategyName;

    @Column(name = "strategy_version", length = 32)
    private String strategyVersion;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "candle_timestamp")
    private Instant candleTimestamp;

    @Column(name = "confidence_score", precision = 10, scale = 6)
    private BigDecimal confidenceScore;

    @Column(name = "probability", precision = 10, scale = 6)
    private BigDecimal probability;

    @Column(name = "trade_quality", length = 32)
    private String tradeQuality;

    @Column(name = "confidence_version", length = 32)
    private String confidenceVersion;

    @Column(name = "confidence_breakdown_json", columnDefinition = "text")
    private String confidenceBreakdownJson;

    @Column(name = "rsi_value", precision = 24, scale = 8)
    private BigDecimal rsiValue;

    @Column(name = "vwap_distance", precision = 24, scale = 8)
    private BigDecimal vwapDistance;

    @Column(name = "atr_value", precision = 24, scale = 8)
    private BigDecimal atrValue;

    @Column(name = "range_high", precision = 24, scale = 8)
    private BigDecimal rangeHigh;

    @Column(name = "range_low", precision = 24, scale = 8)
    private BigDecimal rangeLow;

    @Column(name = "market_regime", length = 32)
    private String marketRegime;

    @Column(name = "rejection_pattern", length = 64)
    private String rejectionPattern;

    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "backtest_run_id")
    private UUID backtestRunId;

    @Column(name = "pipeline", length = 16)
    private String pipeline;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_source", length = 16)
    private SignalProvenance signalSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", length = 32)
    private SignalOwnerType ownerType;

    @Column(name = "lifecycle_status", length = 32)
    private String lifecycleStatus;

    @Column(name = "stop_price", precision = 24, scale = 8)
    private BigDecimal stopPrice;

    @Column(name = "target_price", precision = 24, scale = 8)
    private BigDecimal targetPrice;

    @Column(name = "entry_reference_price", precision = 24, scale = 8)
    private BigDecimal entryReferencePrice;

    @Column(name = "parameter_snapshot_json", columnDefinition = "text")
    private String parameterSnapshotJson;

    @Column(name = "indicator_snapshot_json", columnDefinition = "text")
    private String indicatorSnapshotJson;

    // Outcome lifecycle tracking (populated by outcome-tracking service)
    @Column(name = "outcome_status", length = 32)
    private String outcomeStatus;

    @Column(name = "outcome_time")
    private Instant outcomeTime;

    @Column(name = "entry_price", precision = 24, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 24, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "outcome_comment", length = 500)
    private String outcomeComment;

    /**
     * Terminal exit-dispatch decision: EXIT_PLACED (broker exit legs created) or
     * NO_EXIT_NEEDED (all entry legs rejected/cancelled/absent ??? nothing to unwind).
     * Null = not yet evaluated; the outcome-exit backfill only scans null rows.
     */
    @Column(name = "outcome_exit_disposition", length = 32)
    private String outcomeExitDisposition;

    @Column(name = "realized_pnl", precision = 24, scale = 8)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 24, scale = 8)
    private BigDecimal unrealizedPnl;

    @Column(name = "max_favorable_excursion", precision = 24, scale = 8)
    private BigDecimal maxFavorableExcursion;

    @Column(name = "max_adverse_excursion", precision = 24, scale = 8)
    private BigDecimal maxAdverseExcursion;

    @Column(name = "hit_target")
    private Boolean hitTarget = false;

    @Column(name = "hit_stoploss")
    private Boolean hitStoploss = false;

    @Column(name = "risk_reward_achieved", precision = 10, scale = 4)
    private BigDecimal riskRewardAchieved;

    @Column(name = "execution_latency_ms")
    private Long executionLatencyMs;

    @Column(name = "broker_latency_ms")
    private Long brokerLatencyMs;

    @Column(name = "signal_validity_seconds")
    private Integer signalValiditySeconds;

    @Column(name = "expired")
    private Boolean expired = false;

    @Column(name = "expiry_reason", length = 128)
    private String expiryReason;

    @Column(name = "is_test_trade", nullable = false)
    private Boolean testTrade = false;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "test_run_id")
    private UUID testRunId;

    @Column(name = "test_scenario", length = 64)
    private String testScenario;

    @Column(name = "is_simulation", nullable = false)
    private boolean simulation;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "simulation_run_id")
    private UUID simulationRunId;

    @Column(name = "simulation_scenario", length = 64)
    private String simulationScenario;

    @PrePersist
    void prePersistDefaults() {
        if (expired == null) {
            expired = false;
        }
        if (hitTarget == null) {
            hitTarget = false;
        }
        if (hitStoploss == null) {
            hitStoploss = false;
        }
        if (testTrade == null) {
            testTrade = false;
        }
    }
}
