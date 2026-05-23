package com.stokr.execution.adapter;

import com.stokr.broker.model.BrokerPosition;
import com.stokr.execution.exchange.LatencySimulator;
import com.stokr.execution.exchange.MarginManager;
import com.stokr.execution.exchange.MatchingEngine;
import com.stokr.execution.exchange.OrderBook;
import com.stokr.execution.exchange.SlippageSimulator;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.oms.domain.OrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Full implementation with order book, matching, fills, margin management.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaperExchangeAdapter implements ExecutionAdapter {

    private final SlippageSimulator slippageSimulator;
    private final LatencySimulator latencySimulator;

    @Value("${stokr.paper.starting-capital:1000000}")
    private BigDecimal startingCapital;

    @Value("${stokr.paper.margin-multiplier:5}")
    private BigDecimal marginMultiplier;

    private final Map<UUID, OrderExecutionRequest> orders = new ConcurrentHashMap<>();
    private final Map<UUID, List<FillRecord>> fills = new ConcurrentHashMap<>();
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    private MarginManager marginManager;
    private volatile boolean initialized = false;

    @Override
    public String vendorCode() {
        return "PAPER";
    }

    @Override
    public OrderExecutionResponse execute(OrderExecutionRequest request) {
        try {
            ensureInitialized();
            long startMs = System.currentTimeMillis();

            // Get or create order book for symbol
            OrderBook orderBook = orderBooks.computeIfAbsent(
                    request.getSymbol(),
                    s -> new OrderBook(s)
            );

            // Calculate order value for margin check
            BigDecimal orderValue = request.getQuantity().multiply(
                    request.getLimitPrice() != null
                            ? request.getLimitPrice()
                            : lastPrices.getOrDefault(request.getSymbol(), BigDecimal.valueOf(100))
            );

            // Check margin
            if (!marginManager.checkMarginAvailable(orderValue)) {
                return OrderExecutionResponse.builder()
                        .orderId(request.getOrderId())
                        .status(OrderState.REJECTED)
                        .latencyMs(System.currentTimeMillis() - startMs)
                        .errorCode("INSUFFICIENT_MARGIN")
                        .errorMessage("Insufficient margin: required=" + orderValue +
                                ", available=" + marginManager.getAvailableMargin())
                        .timestamp(Instant.now())
                        .build();
            }

            // Block margin
            marginManager.blockMargin(request.getSymbol(), orderValue);

            // Create matching engine
            MatchingEngine engine = new MatchingEngine(orderBook, slippageSimulator, latencySimulator);

            // Match order
            MatchingEngine.MatchResult result = "MARKET".equals(request.getOrderType())
                    ? engine.matchMarketOrder(request.getOrderId(), request.getSide(),
                    request.getQuantity(), request.getTimestamp())
                    : engine.matchLimitOrder(request.getOrderId(), request.getSide(),
                    request.getQuantity(), request.getLimitPrice(), request.getTimestamp());

            // Convert match result to fills
            List<FillRecord> orderFills = result.getFills().stream()
                    .map(f -> FillRecord.builder()
                            .orderId(f.getOrderId())
                            .fillId(f.getFillId())
                            .quantity(f.getQuantity())
                            .price(f.getPrice())
                            .fillTime(f.getTimestamp())
                            .side(f.getSide())
                            .latencyMs(f.getLatencyMs())
                            .build())
                    .toList();

            fills.put(request.getOrderId(), orderFills);
            orders.put(request.getOrderId(), request);

            long latencyMs = System.currentTimeMillis() - startMs;

            log.info("paper_adapter.execute orderId={} symbol={} status={} fills={} latencyMs={}",
                    request.getOrderId(), request.getSymbol(),
                    result.isFilled() ? "FILLED" : "PARTIAL", result.getFills().size(), latencyMs);

            return OrderExecutionResponse.builder()
                    .orderId(request.getOrderId())
                    .status(result.isFilled() ? OrderState.FILLED : OrderState.PARTIALLY_FILLED)
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

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    marginManager = new MarginManager(startingCapital, marginMultiplier);
                    initialized = true;
                    log.info("paper_adapter.initialized capital={} multiplier={}",
                            startingCapital, marginMultiplier);
                }
            }
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
        // Update last price
        lastPrices.put(tick.getSymbol(), tick.getPrice());

        // TODO: PHASE 3+
        // - Detect target/stop hits
        // - Check circuit breaker conditions
        // - Update position MTM
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
     * Get margin snapshot.
     */
    public MarginManager.MarginSnapshot getMarginSnapshot() {
        ensureInitialized();
        return marginManager.getSnapshot();
    }

    /**
     * Add realized P&L to account.
     */
    public void addRealizedPnl(BigDecimal pnl) {
        ensureInitialized();
        marginManager.addRealizedPnl(pnl);
    }
}
