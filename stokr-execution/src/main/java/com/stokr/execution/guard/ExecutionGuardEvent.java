package com.stokr.execution.guard;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "execution_guard_events")
public class ExecutionGuardEvent extends BaseEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "order_id")
    private UUID orderId;
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "signal_id")
    private UUID signalId;
    @Column(name = "strategy_key", length = 128)
    private String strategyKey;
    @Column(name = "symbol", length = 64)
    private String symbol;
    @Column(name = "side", length = 8)
    private String side;
    @Enumerated(EnumType.STRING)
    @Column(name = "guard_mode", length = 32)
    private ExecutionGuardMode guardMode;
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private ExecutionGuardOutcome outcome;
    @Column(name = "primary_code", length = 64)
    private String primaryCode;
    @Column(name = "message", length = 512)
    private String message;
    @Column(name = "signal_generated_at")
    private Instant signalGeneratedAt;
    @Column(name = "validation_time")
    private Instant validationTime;
    @Column(name = "signal_age_ms")
    private Long signalAgeMs;
    @Column(name = "ttl_ms")
    private Long ttlMs;
    @Column(name = "signal_price", precision = 24, scale = 8)
    private BigDecimal signalPrice;
    @Column(name = "current_ltp", precision = 24, scale = 8)
    private BigDecimal currentLtp;
    @Column(name = "drift_pct", precision = 12, scale = 6)
    private BigDecimal driftPct;
    @Column(name = "allowed_drift_pct", precision = 12, scale = 6)
    private BigDecimal allowedDriftPct;
    @Column(name = "last_tick_at")
    private Instant lastTickAt;
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;
    @Column(name = "stale_duration_ms")
    private Long staleDurationMs;
    @Column(name = "validation_latency_ms")
    private Long validationLatencyMs;
}

