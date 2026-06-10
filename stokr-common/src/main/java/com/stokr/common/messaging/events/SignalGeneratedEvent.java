package com.stokr.common.messaging.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published by Strategy Service when a trading signal is generated.
 * Consumed by Execution Service to place orders.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalGeneratedEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    // Signal identification
    private String signalId;
    private String strategyName;
    private String strategyInstance;

    // Trading details
    private String symbol;
    private String side; // "BUY" or "SELL"
    private Integer quantity;
    private BigDecimal lastTradedPrice;
    private Integer aiScore;

    // Market context
    private BigDecimal dayChange;
    private BigDecimal recentMomentum;
    private BigDecimal volumeFactor;

    // Strategy configuration
    private String executionMode; // "LIVE", "PAPER", "BOTH"
    private String broker; // "ZERODHA", "SIM"

    // Timing
    private Instant generatedAt;
    private Instant scanTimestamp;

    // Risk parameters (informational)
    private BigDecimal suggestedStopLoss;
    private BigDecimal suggestedTakeProfit;

    // Metadata
    private String traderId; // User ID if applicable
    private String correlationId; // For request tracing
}
