package com.stokr.oms.domain;

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

/**
 * Execution snapshots are append-only financial truth: updates/deletes must never occur in services.
 */
@Getter
@Setter
@Entity
@Table(name = "oms_executions")
public class OmsExecution extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OmsOrder order;

    @Column(name = "broker_execution_id", length = 128)
    private String brokerExecutionId;

    @Column(name = "execution_sequence", updatable = false)
    private Long executionSequence;

    @Column(name = "execution_hash", length = 128, updatable = false)
    private String executionHash;

    @Column(name = "filled_qty", nullable = false, precision = 24, scale = 8)
    private BigDecimal filledQty = BigDecimal.ZERO;

    @Column(name = "avg_price", precision = 24, scale = 8)
    private BigDecimal avgPrice;

    @Column(name = "execution_kind", length = 16)
    private String executionKind;

    @Column(name = "fill_time")
    private Instant fillTime;

    @Column(name = "execution_timestamp", updatable = false)
    private Instant executionTimestamp;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "slippage_bps", precision = 24, scale = 8)
    private BigDecimal slippageBps;

    @Column(name = "spread_bps", precision = 24, scale = 8)
    private BigDecimal spreadBps;

    @Column(name = "reference_price", precision = 24, scale = 8)
    private BigDecimal referencePrice;

    @Column(name = "replay_source", length = 32, updatable = false)
    private String replaySource;

    @Column(name = "replay_run_id", updatable = false)
    private java.util.UUID replayRunId;

    @Column(name = "is_simulation", nullable = false)
    private boolean simulation;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.UUID)
    @Column(name = "simulation_run_id")
    private java.util.UUID simulationRunId;

    @Column(name = "simulation_scenario", length = 64)
    private String simulationScenario;
}
