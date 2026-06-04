package com.stokr.execution.tracking;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signal_execution_tracks", indexes = {
        @Index(name = "idx_signal_id", columnList = "signal_id"),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignalExecutionTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "signal_id", nullable = false)
    private UUID signalId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "strategy_key", nullable = false)
    private String strategyKey;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "signal_type")
    private String signalType;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "broker_order_id")
    private String brokerOrderId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private SignalExecutionStatus status;

    @Column(name = "execution_mode")
    private String executionMode;

    @Column(name = "broker_vendor")
    private String brokerVendor;

    @Column(name = "side")
    private String side;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "entry_price")
    private BigDecimal entryPrice;

    @Column(name = "current_step", length = 500)
    private String currentStep;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "filled_at")
    private Instant filledAt;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public enum SignalExecutionStatus {
        GENERATED,           // Signal created
        DISPATCHED,          // Sent to trader terminal
        VALIDATION_FAILED,   // Strategy/gate validation failed
        SIZING_FAILED,       // Position sizing rejected
        RISK_FAILED,         // Risk engine rejected
        EXPOSURE_FAILED,     // Exposure limits exceeded
        BROKER_TRUTH_FAILED, // Position truth validation failed
        ORDER_CREATED,       // Order created in OMS
        SUBMITTED,           // Submitted to broker
        ACCEPTED,            // Broker accepted
        PARTIALLY_FILLED,    // Partially filled
        FILLED,              // Completely filled
        CANCELLED,           // Cancelled
        REJECTED,            // Rejected by broker
        TIMEOUT,             // Execution timeout
        RETRYING,            // Retrying after failure
        COMPLETED            // Successfully completed
    }
}
