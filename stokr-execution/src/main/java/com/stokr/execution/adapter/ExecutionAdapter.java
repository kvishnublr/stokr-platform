package com.stokr.execution.adapter;

import com.stokr.broker.model.BrokerPosition;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified adapter contract for execution.
 * Implementations handle LIVE (broker API) or PAPER (simulated exchange).
 *
 * CONTRACT:
 * - Both adapters implement identical external behavior
 * - Only internal implementation differs
 * - No mode conditionals in strategy code
 * - All adapters must support market ticks for position updates
 */
public interface ExecutionAdapter {

    /**
     * Get adapter vendor code (e.g., "ZERODHA", "PAPER", "HYBRID").
     */
    String vendorCode();

    /**
     * Submit order for execution.
     *
     * @param request Order execution request
     * @return Confirmation with order_id or error
     * @throws ExecutionAdapterException on critical failures
     */
    OrderExecutionResponse execute(OrderExecutionRequest request);

    /**
     * Cancel order by ID.
     *
     * @param orderId Order ID
     * @return true if cancelled, false if not found or already terminal
     */
    boolean cancel(UUID orderId);

    /**
     * Get current order status.
     *
     * @param orderId Order ID
     * @return Status or empty if not found
     */
    Optional<OrderState> getOrderStatus(UUID orderId);

    /**
     * Get all fills for an order.
     *
     * @param orderId Order ID
     * @return List of fills
     */
    List<FillRecord> getFills(UUID orderId);

    /**
     * Get all current positions.
     *
     * @return List of positions
     */
    List<BrokerPosition> getPositions();

    /**
     * Called when market data tick arrives.
     * Used for:
     * - Matching orders (paper)
     * - Updating positions (both)
     * - Detecting SL/Target hits (both)
     *
     * @param tick Market data tick
     */
    void onMarketTick(MarketdataTick tick);

    /**
     * Get adapter health status.
     *
     * @return Health status
     */
    HealthStatus healthCheck();

    /**
     * Order execution request (input contract).
     */
    @Data
    @Builder
    class OrderExecutionRequest {
        private UUID orderId;
        private String symbol;
        private String side; // BUY or SELL
        private BigDecimal quantity;
        private String orderType; // LIMIT, MARKET
        private BigDecimal limitPrice;
        private BigDecimal stopPrice;
        private String product; // MIS or CNC
        private String validity; // DAY, GTC, IOC
        private Instant timestamp;
        private String strategyKey;
        private UUID signalId;
    }

    /**
     * Order execution response (output contract).
     */
    @Data
    @Builder
    class OrderExecutionResponse {
        private UUID orderId;
        private OrderState status;
        private String brokerOrderId; // For LIVE adapters
        private Long latencyMs;
        private String errorCode; // If failed
        private String errorMessage; // If failed
        private Instant timestamp;
    }

    /**
     * Fill record for a partial or complete fill.
     */
    @Data
    @Builder
    class FillRecord {
        private UUID orderId;
        private String fillId;
        private BigDecimal quantity;
        private BigDecimal price;
        private Instant fillTime;
        private String side; // BUY or SELL
        private Long latencyMs;
    }

    /**
     * Adapter health status.
     */
    @Data
    @Builder
    class HealthStatus {
        private String status; // OK, DEGRADED, CRITICAL
        private Long latencyMs;
        private Instant lastCheck;
        private String errorMessage; // If not OK
        private int queueDepth; // For monitoring
        private String connectivity; // OK, RECONNECTING, DISCONNECTED
    }

    /**
     * Adapter-specific exception.
     */
    class ExecutionAdapterException extends RuntimeException {
        public ExecutionAdapterException(String message) {
            super(message);
        }

        public ExecutionAdapterException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
