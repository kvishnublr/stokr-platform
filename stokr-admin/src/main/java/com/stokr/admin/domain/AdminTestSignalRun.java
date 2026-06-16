package com.stokr.admin.domain;

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
@Table(name = "admin_test_signal_runs")
public class AdminTestSignalRun extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "trader_user_id", nullable = false)
    private UUID traderUserId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "broker_account_id")
    private UUID brokerAccountId;

    @Column(name = "broker_vendor", length = 32)
    private String brokerVendor;

    @Column(name = "strategy_key", nullable = false, length = 128)
    private String strategyKey;

    @Column(name = "strategy_template", length = 128)
    private String strategyTemplate;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "side", nullable = false, length = 8)
    private String side;

    @Column(name = "quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "product_type", length = 32)
    private String productType;

    @Column(name = "order_type", length = 32)
    private String orderType;

    @Column(name = "exchange", length = 16)
    private String exchange;

    @Column(name = "requested_price", precision = 24, scale = 8)
    private BigDecimal requestedPrice;

    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    @Column(name = "force_quantity_one", nullable = false)
    private boolean forceQuantityOne = true;

    @Column(name = "dry_run_only", nullable = false)
    private boolean dryRunOnly = true;

    @Column(name = "skip_actual_broker_execution", nullable = false)
    private boolean skipActualBrokerExecution = true;

    @Column(name = "simulate_rejection", nullable = false)
    private boolean simulateRejection = false;

    @Column(name = "simulate_timeout", nullable = false)
    private boolean simulateTimeout = false;

    @Column(name = "simulate_stale_websocket", nullable = false)
    private boolean simulateStaleWebsocket = false;

    @Column(name = "simulate_margin_failure", nullable = false)
    private boolean simulateMarginFailure = false;

    @Column(name = "simulate_broker_disconnect", nullable = false)
    private boolean simulateBrokerDisconnect = false;

    @Column(name = "auto_square_off_minutes")
    private Integer autoSquareOffMinutes;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "final_status", length = 16)
    private String finalStatus;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "signal_id")
    private UUID signalId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "report_json", columnDefinition = "text")
    private String reportJson;

    @Column(name = "diagnostics_json", columnDefinition = "text")
    private String diagnosticsJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "auto_square_off_due_at")
    private Instant autoSquareOffDueAt;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "square_off_order_id")
    private UUID squareOffOrderId;

    @Column(name = "square_off_status", length = 32)
    private String squareOffStatus;

    @Column(name = "square_off_completed_at")
    private Instant squareOffCompletedAt;
}
