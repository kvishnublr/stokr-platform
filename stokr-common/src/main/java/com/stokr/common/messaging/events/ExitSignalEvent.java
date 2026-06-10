package com.stokr.common.messaging.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published by Core Trading System when a position should be exited.
 * Consumed by Execution Service to place exit orders.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExitSignalEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    // Exit identification
    private String exitSignalId;
    private String orderId; // Reference to entry order

    // Trading details
    private String symbol;
    private String side; // "SELL" or "BUY" (opposite of entry)
    private Integer quantity;
    private BigDecimal currentMarketPrice;

    // Exit reason
    private String reason; // "STOP_LOSS_HIT", "TAKE_PROFIT_HIT", "MARKET_CLOSE", "AISCORE_DROP", "MANUAL_EXIT", "EXTERNAL_EXIT"
    private String reasonDetails;

    // Exit target price (if applicable)
    private BigDecimal exitTargetPrice;

    // Position P&L
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal pnlAmount;
    private BigDecimal pnlPercentage;

    // Execution mode
    private String executionMode; // "LIVE", "PAPER", "BOTH"
    private String broker;

    // Timing
    private Instant entryTime;
    private Instant holdDuration; // How long position was held
    private Instant exitDetectedAt;
    private Instant exitRequestedAt;

    // Metadata
    private String traderId;
    private String correlationId;
    private Boolean urgent; // If true, emergency/market-close exit
}
