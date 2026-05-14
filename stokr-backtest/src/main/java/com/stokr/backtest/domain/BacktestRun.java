package com.stokr.backtest.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "backtest_runs")
public class BacktestRun extends BaseEntity {

    @Column(name = "strategy_key", nullable = false, length = 128)
    private String strategyKey;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BacktestStatus status = BacktestStatus.QUEUED;

    @Column(name = "seed", nullable = false)
    private long seed;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "timeframe", length = 16)
    private String timeframe = "1m";

    @Column(name = "range_start")
    private Instant rangeStart;

    @Column(name = "range_end")
    private Instant rangeEnd;

    /** UX label e.g. SIMULATED_SLIPPAGE_DEFAULT — does not change execution pipeline unless wired. */
    @Column(name = "execution_profile", length = 32)
    private String executionProfile;

    /** Canonical PR-2 execution envelope JSON (sorted keys in persistence layer). */
    @Column(name = "execution_request_json", columnDefinition = "text")
    private String executionRequestJson;

    @Column(name = "metadata_schema_version")
    private Integer metadataSchemaVersion;

    @Column(name = "strategy_definition_version")
    private Long strategyDefinitionVersion;

    @Column(name = "execution_mode", length = 32)
    private String executionMode;

    @Column(name = "fee_model", length = 64)
    private String feeModel;

    @Column(name = "slippage_model", length = 64)
    private String slippageModel;
}
