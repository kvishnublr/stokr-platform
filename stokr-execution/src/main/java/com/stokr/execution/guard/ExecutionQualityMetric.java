package com.stokr.execution.guard;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "execution_quality_metrics")
public class ExecutionQualityMetric extends BaseEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "signal_id")
    private UUID signalId;
    @Column(name = "symbol", length = 64)
    private String symbol;
    @Column(name = "strategy_key", length = 128)
    private String strategyKey;
    @Column(name = "expected_price", precision = 24, scale = 8)
    private BigDecimal expectedPrice;
    @Column(name = "actual_fill_price", precision = 24, scale = 8)
    private BigDecimal actualFillPrice;
    @Column(name = "realized_slippage_pct", precision = 12, scale = 6)
    private BigDecimal realizedSlippagePct;
    @Column(name = "fill_latency_ms")
    private Long fillLatencyMs;
    @Column(name = "spread_at_execution_pct", precision = 12, scale = 6)
    private BigDecimal spreadAtExecutionPct;
    @Column(name = "volatility_at_execution_pct", precision = 12, scale = 6)
    private BigDecimal volatilityAtExecutionPct;
    @Column(name = "captured_at")
    private Instant capturedAt;
}

