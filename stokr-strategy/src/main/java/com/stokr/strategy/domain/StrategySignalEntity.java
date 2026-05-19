package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import com.stokr.strategy.signals.SignalType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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

    public static final String STRATEGY_KEY = "MEAN_REVERSION_RANGE_FADE";
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

    @Column(name = "realized_pnl", precision = 24, scale = 8)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 24, scale = 8)
    private BigDecimal unrealizedPnl;

    @Column(name = "max_favorable_excursion", precision = 24, scale = 8)
    private BigDecimal maxFavorableExcursion;

    @Column(name = "max_adverse_excursion", precision = 24, scale = 8)
    private BigDecimal maxAdverseExcursion;

    @Column(name = "hit_target")
    private Boolean hitTarget;

    @Column(name = "hit_stoploss")
    private Boolean hitStoploss;

    @Column(name = "risk_reward_achieved", precision = 10, scale = 4)
    private BigDecimal riskRewardAchieved;

    @Column(name = "execution_latency_ms")
    private Long executionLatencyMs;

    @Column(name = "broker_latency_ms")
    private Long brokerLatencyMs;

    @Column(name = "signal_validity_seconds")
    private Integer signalValiditySeconds;

    @Column(name = "expired")
    private Boolean expired;

    @Column(name = "expiry_reason", length = 128)
    private String expiryReason;
}
