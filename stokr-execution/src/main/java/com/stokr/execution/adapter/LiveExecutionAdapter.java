package com.stokr.execution.adapter;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.model.BrokerCredentials;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.registry.BrokerAdapterRegistry;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.oms.domain.OrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live execution adapter wrapping broker API.
 * Routes orders to actual brokers (Zerodha, Upstox, etc.).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveExecutionAdapter implements ExecutionAdapter {

    private final BrokerAdapterRegistry brokerAdapterRegistry;
    private final Map<UUID, OrderExecutionRequest> requestCache = new ConcurrentHashMap<>();
    private final Map<UUID, List<FillRecord>> fillCache = new ConcurrentHashMap<>();

    @Override
    public String vendorCode() {
        return "LIVE";
    }

    @Override
    public OrderExecutionResponse execute(OrderExecutionRequest request) {
        try {
            long startMs = System.currentTimeMillis();

            // TODO: In production, extract broker vendor from order or request
            // For now, default to Zerodha
            String brokerVendor = "ZERODHA";
            BrokerAdapter brokerAdapter = brokerAdapterRegistry.get(brokerVendor);

            // Build broker-specific request (BrokerOrderRequest is a record)
            BrokerOrderRequest brokerRequest = new BrokerOrderRequest(
                    request.getSymbol(),
                    request.getSide(),
                    request.getOrderType(),
                    request.getQuantity(),
                    request.getLimitPrice(),
                    request.getOrderId().toString()
            );

            // Submit to broker
            BrokerOrderResponse brokerResponse = brokerAdapter.placeOrder(brokerRequest);

            long latencyMs = System.currentTimeMillis() - startMs;

            // Cache request for later retrieval
            requestCache.put(request.getOrderId(), request);

            // Map broker response to unified response
            OrderState status = brokerResponse.status() != null
                    ? OrderState.valueOf(brokerResponse.status())
                    : OrderState.SUBMITTED;

            return OrderExecutionResponse.builder()
                    .orderId(request.getOrderId())
                    .status(status)
                    .brokerOrderId(brokerResponse.brokerOrderId() != null ? brokerResponse.brokerOrderId().toString() : null)
                    .latencyMs(latencyMs)
                    .timestamp(Instant.now())
                    .build();

        } catch (Exception ex) {
            log.error("live_adapter.execute.failed orderId={} symbol={} error={}",
                    request.getOrderId(), request.getSymbol(), ex.getMessage(), ex);

            return OrderExecutionResponse.builder()
                    .orderId(request.getOrderId())
                    .status(OrderState.FAILED)
                    .latencyMs(System.currentTimeMillis())
                    .errorCode("BROKER_ERROR")
                    .errorMessage(ex.getMessage())
                    .timestamp(Instant.now())
                    .build();
        }
    }

    @Override
    public boolean cancel(UUID orderId) {
        try {
            // TODO: Implement broker cancellation
            log.info("live_adapter.cancel orderId={}", orderId);
            return true;
        } catch (Exception ex) {
            log.error("live_adapter.cancel.failed orderId={} error={}",
                    orderId, ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public Optional<OrderState> getOrderStatus(UUID orderId) {
        try {
            // TODO: Implement broker order status retrieval
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("live_adapter.status.failed orderId={} error={}",
                    orderId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<FillRecord> getFills(UUID orderId) {
        return fillCache.getOrDefault(orderId, List.of());
    }

    @Override
    public List<BrokerPosition> getPositions() {
        try {
            // TODO: Implement position retrieval from broker
            return List.of();
        } catch (Exception ex) {
            log.warn("live_adapter.positions.failed error={}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public void onMarketTick(MarketdataTick tick) {
        // Live adapter doesn't need market ticks for matching
        // (broker provides fills directly)
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            long startMs = System.currentTimeMillis();
            // TODO: Implement broker connectivity check
            long latencyMs = System.currentTimeMillis() - startMs;

            return HealthStatus.builder()
                    .status("OK")
                    .latencyMs(latencyMs)
                    .lastCheck(Instant.now())
                    .connectivity("OK")
                    .build();
        } catch (Exception ex) {
            return HealthStatus.builder()
                    .status("CRITICAL")
                    .errorMessage(ex.getMessage())
                    .lastCheck(Instant.now())
                    .connectivity("DISCONNECTED")
                    .build();
        }
    }
}
