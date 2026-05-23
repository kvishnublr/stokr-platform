package com.stokr.execution.adapter;

import com.stokr.broker.model.BrokerPosition;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.oms.domain.OrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper exchange adapter for simulated trading.
 * Implements a simulated exchange with order book, matching, fills.
 *
 * PHASE 2: Full implementation with:
 * - OrderBook (per-symbol)
 * - MatchingEngine (FIFO)
 * - SlippageSimulator
 * - LatencySimulator
 * - MarginManager
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaperExchangeAdapter implements ExecutionAdapter {

    private final Map<UUID, OrderExecutionRequest> orders = new ConcurrentHashMap<>();
    private final Map<UUID, List<FillRecord>> fills = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    // Virtual funds
    private BigDecimal virtualCapital = BigDecimal.valueOf(1_000_000);

    @Override
    public String vendorCode() {
        return "PAPER";
    }

    @Override
    public OrderExecutionResponse execute(OrderExecutionRequest request) {
        try {
            long startMs = System.currentTimeMillis();

            // TODO: PHASE 2
            // - Check margin
            // - Create order in order book
            // - Match against existing liquidity
            // - Generate fills with slippage/latency simulation
            // - Record fills

            log.info("paper_adapter.execute orderId={} symbol={} qty={} price={}",
                    request.getOrderId(), request.getSymbol(), request.getQuantity(), request.getLimitPrice());

            // For now, immediately fill at reference price
            BigDecimal fillPrice = request.getLimitPrice() != null
                    ? request.getLimitPrice()
                    : lastPrices.getOrDefault(request.getSymbol(), BigDecimal.valueOf(100));

            long latencyMs = System.currentTimeMillis() - startMs + 25; // simulate 25ms latency

            // Record order
            orders.put(request.getOrderId(), request);

            // Create fill record
            FillRecord fill = FillRecord.builder()
                    .orderId(request.getOrderId())
                    .fillId("paper-" + request.getOrderId())
                    .quantity(request.getQuantity())
                    .price(fillPrice)
                    .fillTime(Instant.now())
                    .side(request.getSide())
                    .latencyMs(latencyMs)
                    .build();

            fills.put(request.getOrderId(), List.of(fill));

            return OrderExecutionResponse.builder()
                    .orderId(request.getOrderId())
                    .status(OrderState.FILLED)
                    .latencyMs(latencyMs)
                    .timestamp(Instant.now())
                    .build();

        } catch (Exception ex) {
            log.error("paper_adapter.execute.failed orderId={} symbol={} error={}",
                    request.getOrderId(), request.getSymbol(), ex.getMessage(), ex);

            return OrderExecutionResponse.builder()
                    .orderId(request.getOrderId())
                    .status(OrderState.REJECTED)
                    .latencyMs(System.currentTimeMillis())
                    .errorCode("SIM_ERROR")
                    .errorMessage(ex.getMessage())
                    .timestamp(Instant.now())
                    .build();
        }
    }

    @Override
    public boolean cancel(UUID orderId) {
        try {
            log.info("paper_adapter.cancel orderId={}", orderId);
            orders.remove(orderId);
            return true;
        } catch (Exception ex) {
            log.error("paper_adapter.cancel.failed orderId={} error={}",
                    orderId, ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public Optional<OrderState> getOrderStatus(UUID orderId) {
        if (fills.containsKey(orderId)) {
            return Optional.of(OrderState.FILLED);
        }
        if (orders.containsKey(orderId)) {
            return Optional.of(OrderState.OPEN);
        }
        return Optional.empty();
    }

    @Override
    public List<FillRecord> getFills(UUID orderId) {
        return fills.getOrDefault(orderId, List.of());
    }

    @Override
    public List<BrokerPosition> getPositions() {
        // TODO: PHASE 2 - return positions from position manager
        return List.of();
    }

    @Override
    public void onMarketTick(MarketdataTick tick) {
        // Update last price and check for order matches
        lastPrices.put(tick.getSymbol(), tick.getPrice());

        // TODO: PHASE 2
        // - Match orders in order book
        // - Detect stop/target hits
        // - Generate fills
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.builder()
                .status("OK")
                .latencyMs(5L)
                .lastCheck(Instant.now())
                .connectivity("OK")
                .build();
    }

    /**
     * Get virtual capital available.
     */
    public BigDecimal getVirtualCapital() {
        return virtualCapital;
    }

    /**
     * Set virtual capital (for testing).
     */
    public void setVirtualCapital(BigDecimal amount) {
        this.virtualCapital = amount;
    }
}
