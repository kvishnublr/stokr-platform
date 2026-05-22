package com.stokr.oms.domain;

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
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "oms_orders")
public class OmsOrder extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "strategy_key", length = 128)
    private String strategyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 16)
    private ExecutionMode executionMode = ExecutionMode.SIMULATED;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "side", nullable = false, length = 8)
    private String side;

    @Column(name = "order_type", nullable = false, length = 32)
    private String orderType;

    @Column(name = "quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "limit_price", precision = 24, scale = 8)
    private BigDecimal limitPrice;

    @Column(name = "stop_price", precision = 24, scale = 8)
    private BigDecimal stopPrice;

    @Column(name = "target_price", precision = 24, scale = 8)
    private BigDecimal targetPrice;

    @Column(name = "entry_reference_price", precision = 24, scale = 8)
    private BigDecimal entryReferencePrice;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "backtest_run_id")
    private UUID backtestRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 64)
    private OrderState state = OrderState.CREATED;

    @Column(name = "broker_vendor", length = 32)
    private String brokerVendor;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "broker_order_id")
    private UUID brokerOrderId;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "paired_order_id")
    private UUID pairedOrderId;

    @Column(name = "is_test_trade", nullable = false)
    private boolean testTrade = false;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "test_run_id")
    private UUID testRunId;
}
