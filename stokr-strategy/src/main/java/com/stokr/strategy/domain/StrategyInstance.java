package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "strategy_instances")
public class StrategyInstance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private StrategyDefinition definition;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode = "SIMULATED";

    @Column(name = "allocation_amount", precision = 24, scale = 8)
    private BigDecimal allocationAmount;

    @Column(name = "risk_multiplier", precision = 24, scale = 8)
    private BigDecimal riskMultiplier;

    @Column(name = "max_daily_loss", precision = 24, scale = 8)
    private BigDecimal maxDailyLoss;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "runtime_state", nullable = false, length = 32)
    private String runtimeState = "STOPPED";

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "orchestration_state", nullable = false, length = 32)
    private String orchestrationState = "MANAGED";
}
