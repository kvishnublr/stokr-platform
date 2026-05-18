package com.stokr.execution.guard;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "execution_timeline_events")
public class ExecutionTimelineEvent extends BaseEntity {
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "timeline_id", nullable = false)
    private UUID timelineId;
    @Column(name = "correlation_id", length = 96)
    private String correlationId;
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "signal_id")
    private UUID signalId;
    @Column(name = "strategy_key", length = 128)
    private String strategyKey;
    @Column(name = "symbol", length = 64)
    private String symbol;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;
    @Column(name = "latency_ms")
    private Long latencyMs;
    @Column(name = "market_snapshot", columnDefinition = "jsonb")
    private String marketSnapshot;
    @Column(name = "guard_outcome", length = 32)
    private String guardOutcome;
    @Column(name = "severity", length = 16)
    private String severity;
}

