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
@Table(name = "backtest_jobs")
public class BacktestJob extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BacktestJobStatus status = BacktestJobStatus.QUEUED;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "total_bars", nullable = false)
    private int totalBars;

    @Column(name = "processed_bars", nullable = false)
    private int processedBars;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "metadata_schema_version")
    private Integer metadataSchemaVersion;

    @Column(name = "strategy_definition_version")
    private Long strategyDefinitionVersion;

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    @Column(name = "replay_diagnosis", length = 32)
    private String replayDiagnosis;

    @Column(name = "replay_candles_expected", nullable = false)
    private int replayCandlesExpected;

    @Column(name = "replay_candles_processed", nullable = false)
    private int replayCandlesProcessed;

    @Column(name = "replay_signals_emitted", nullable = false)
    private int replaySignalsEmitted;

    @Column(name = "replay_execution_events", nullable = false)
    private int replayExecutionEvents;

    @Column(name = "replay_duration_ms")
    private Long replayDurationMs;
}
