package com.stokr.strategy.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "strategy_exit_telemetry")
public class StrategyExitTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "exit_time", nullable = false)
    private Instant exitTime;

    @Column(name = "hold_seconds", nullable = false)
    private long holdSeconds;

    @Column(name = "exit_category", nullable = false, length = 32)
    private String exitCategory;

    @Column(name = "exit_reason", nullable = false, length = 512)
    private String exitReason;

    @Column(name = "unrealized_pnl_peak", precision = 24, scale = 8)
    private BigDecimal unrealizedPnlPeak;

    @Column(name = "unrealized_pnl_trough", precision = 24, scale = 8)
    private BigDecimal unrealizedPnlTrough;

    @Column(name = "pressure_score_at_exit", precision = 12, scale = 6)
    private BigDecimal pressureScoreAtExit;

    @Column(name = "min_hold_bypassed", nullable = false)
    private boolean minHoldBypassed;

    @Column(name = "pressure_trigger", length = 64)
    private String pressureTrigger;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
