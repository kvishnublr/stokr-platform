package com.stokr.execution.dispatcher;

import com.stokr.broker.model.BrokerPosition;
import com.stokr.execution.adapter.ExecutionAdapter;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes all orders to appropriate execution adapter based on mode.
 * Decouples strategies from execution environment.
 *
 * CONTRACT:
 * - Strategy code calls dispatcher, not specific adapters
 * - Dispatcher selects adapter invisibly
 * - Same signal→order→fill→position→PnL flow regardless of adapter
 */
@Component
@Slf4j
public class ExecutionDispatcher {

    private final Map<ExecutionMode, ExecutionAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * Register an adapter for a specific execution mode.
     *
     * @param mode LIVE, PAPER, HYBRID, SIMULATED
     * @param adapter Implementation (BrokerAdapter, PaperExchangeAdapter, etc.)
     */
    public void registerAdapter(ExecutionMode mode, ExecutionAdapter adapter) {
        adapters.put(mode, adapter);
        log.info("executor.adapter.registered mode={} vendor={}", mode, adapter.vendorCode());
    }

    /**
     * Submit order for execution.
     * Selects appropriate adapter based on order's execution mode.
     *
     * @param request Order execution request
     * @param mode Execution mode
     * @return Confirmation with order_id or error
     */
    public ExecutionAdapter.OrderExecutionResponse execute(
            ExecutionAdapter.OrderExecutionRequest request,
            ExecutionMode mode) {

        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            throw new ExecutionDispatcherException(
                    "No adapter registered for mode: " + mode
            );
        }

        try {
            log.info("executor.dispatch orderId={} mode={} vendor={} symbol={}",
                    request.getOrderId(), mode, adapter.vendorCode(), request.getSymbol());

            ExecutionAdapter.OrderExecutionResponse response = adapter.execute(request);

            log.info("executor.dispatch.complete orderId={} status={} latencyMs={}",
                    request.getOrderId(), response.getStatus(), response.getLatencyMs());

            return response;
        } catch (Exception ex) {
            log.error("executor.dispatch.failed orderId={} mode={} error={}",
                    request.getOrderId(), mode, ex.getMessage(), ex);
            throw new ExecutionDispatcherException(
                    "Execution failed for order " + request.getOrderId(),
                    ex
            );
        }
    }

    /**
     * Cancel order via appropriate adapter.
     *
     * @param orderId Order ID
     * @param mode Execution mode
     * @return true if cancelled
     */
    public boolean cancel(UUID orderId, ExecutionMode mode) {
        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            log.warn("executor.cancel.no_adapter orderId={} mode={}", orderId, mode);
            return false;
        }

        try {
            return adapter.cancel(orderId);
        } catch (Exception ex) {
            log.error("executor.cancel.failed orderId={} mode={} error={}",
                    orderId, mode, ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Get order status via appropriate adapter.
     *
     * @param orderId Order ID
     * @param mode Execution mode
     * @return Status
     */
    public Optional<OrderState> getOrderStatus(UUID orderId, ExecutionMode mode) {
        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            return Optional.empty();
        }

        try {
            return adapter.getOrderStatus(orderId);
        } catch (Exception ex) {
            log.warn("executor.status.failed orderId={} mode={} error={}",
                    orderId, mode, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all fills for an order via appropriate adapter.
     *
     * @param orderId Order ID
     * @param mode Execution mode
     * @return List of fills
     */
    public List<ExecutionAdapter.FillRecord> getFills(UUID orderId, ExecutionMode mode) {
        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            return List.of();
        }

        try {
            return adapter.getFills(orderId);
        } catch (Exception ex) {
            log.warn("executor.fills.failed orderId={} mode={} error={}",
                    orderId, mode, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Get all positions via appropriate adapter.
     *
     * @param mode Execution mode
     * @return List of positions
     */
    public List<BrokerPosition> getPositions(ExecutionMode mode) {
        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            return List.of();
        }

        try {
            return adapter.getPositions();
        } catch (Exception ex) {
            log.warn("executor.positions.failed mode={} error={}", mode, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Route market tick to appropriate adapter for position updates/order matching.
     *
     * @param tick Market data tick
     * @param mode Execution mode
     */
    public void onMarketTick(MarketdataTick tick, ExecutionMode mode) {
        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            return;
        }

        try {
            adapter.onMarketTick(tick);
        } catch (Exception ex) {
            log.warn("executor.tick.failed symbol={} mode={} error={}",
                    tick.getSymbol(), mode, ex.getMessage());
        }
    }

    /**
     * Get adapter health status.
     *
     * @param mode Execution mode
     * @return Health status
     */
    public ExecutionAdapter.HealthStatus healthCheck(ExecutionMode mode) {
        ExecutionAdapter adapter = getAdapter(mode);
        if (adapter == null) {
            return ExecutionAdapter.HealthStatus.builder()
                    .status("CRITICAL")
                    .errorMessage("No adapter registered for mode: " + mode)
                    .connectivity("DISCONNECTED")
                    .build();
        }

        try {
            return adapter.healthCheck();
        } catch (Exception ex) {
            log.warn("executor.health.check_failed mode={} error={}", mode, ex.getMessage());
            return ExecutionAdapter.HealthStatus.builder()
                    .status("CRITICAL")
                    .errorMessage(ex.getMessage())
                    .connectivity("DISCONNECTED")
                    .build();
        }
    }

    /**
     * Get adapter for mode, or null if not registered.
     */
    private ExecutionAdapter getAdapter(ExecutionMode mode) {
        if (mode == null) {
            log.warn("executor.adapter.null_mode");
            return null;
        }
        return adapters.get(mode);
    }

    /**
     * Dispatcher-specific exception.
     */
    public static class ExecutionDispatcherException extends RuntimeException {
        public ExecutionDispatcherException(String message) {
            super(message);
        }

        public ExecutionDispatcherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
