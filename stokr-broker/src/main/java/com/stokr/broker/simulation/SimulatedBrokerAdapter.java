package com.stokr.broker.simulation;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.model.BrokerCredentials;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.model.BrokerTick;
import com.stokr.common.simulation.SimulatedBrokerOutcome;
import com.stokr.common.simulation.SimulationScenarioContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Broker adapter for E2E simulation — no external API calls.
 */
@Component
public class SimulatedBrokerAdapter implements BrokerAdapter {

    @Override
    public String vendorCode() {
        return "SIMULATED";
    }

    @Override
    public void authenticate(BrokerCredentials credentials) {
        // no-op
    }

    @Override
    public BrokerOrderResponse placeOrder(BrokerOrderRequest request) {
        SimulatedBrokerOutcome outcome = SimulationScenarioContext.brokerOutcome();
        UUID brokerOrderId = UUID.randomUUID();
        return switch (outcome) {
            case FILLED -> new BrokerOrderResponse(
                    brokerOrderId,
                    "COMPLETE",
                    request.symbol(),
                    request.quantity(),
                    "SIM-" + brokerOrderId.toString().substring(0, 8)
            );
            case PARTIAL_FILL -> new BrokerOrderResponse(
                    brokerOrderId,
                    "PARTIALLY_FILLED",
                    request.symbol(),
                    partialQty(request.quantity()),
                    "SIM-P-" + brokerOrderId.toString().substring(0, 8)
            );
            case REJECTED -> new BrokerOrderResponse(
                    brokerOrderId,
                    "REJECTED",
                    request.symbol(),
                    BigDecimal.ZERO,
                    null
            );
            case CANCELLED -> new BrokerOrderResponse(
                    brokerOrderId,
                    "CANCELLED",
                    request.symbol(),
                    BigDecimal.ZERO,
                    null
            );
            case MARKET_CLOSED -> new BrokerOrderResponse(
                    brokerOrderId,
                    "REJECTED",
                    request.symbol(),
                    BigDecimal.ZERO,
                    null
            );
        };
    }

    @Override
    public BrokerOrderResponse modifyOrder(UUID brokerOrderId, BrokerOrderRequest request) {
        return placeOrder(request);
    }

    @Override
    public void cancelOrder(UUID brokerOrderId) {
        // no-op
    }

    @Override
    public List<BrokerPosition> getPositions() {
        return List.of();
    }

    @Override
    public List<BrokerOrderResponse> getOrders() {
        return List.of();
    }

    @Override
    public void streamTicks(List<String> symbols, Consumer<BrokerTick> onTick) {
        // harness uses marketdata module for ticks
    }

    private static BigDecimal partialQty(BigDecimal qty) {
        if (qty == null) {
            return BigDecimal.ONE;
        }
        return qty.multiply(BigDecimal.valueOf(0.5)).setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ONE);
    }
}
