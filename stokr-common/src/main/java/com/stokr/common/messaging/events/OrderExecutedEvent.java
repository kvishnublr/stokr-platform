package com.stokr.common.messaging.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published by Execution Service when an order is executed.
 * Consumed by Core Trading System to update position state.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderExecutedEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    // Order identification
    private String orderId;
    private String signalId; // Reference to generating signal
    private String idempotencyKey; // For deduplication

    // Trading details
    private String symbol;
    private String side; // "BUY" or "SELL"
    private Integer requestedQuantity;
    private Integer filledQuantity;

    // Execution details
    private BigDecimal filledPrice;
    private String brokerOrderId;
    private String executionMode; // "LIVE", "PAPER", "BOTH"
    private String broker; // "ZERODHA", "SIM"

    // Order state
    private String status; // "FILLED", "PARTIAL", "REJECTED", "CANCELLED"
    private String rejectionReason; // If rejected

    // Validation results
    private Boolean riskCheckPassed;
    private String riskCheckDetails;

    // Timing
    private Instant signalReceivedAt;
    private Instant riskCheckCompletedAt;
    private Instant brokerSubmittedAt;
    private Instant brokerFilledAt;
    private Instant executedAt;

    // Performance metrics
    private Long riskCheckDurationMs;
    private Long brokerSubmitDurationMs;
    private Long totalDurationMs;

    // Metadata
    private String traderId;
    private String correlationId;
    private String executionServiceInstance; // Which instance executed this
}
