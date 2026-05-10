package com.stokr.broker.adapter;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.model.BrokerCredentials;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.model.BrokerTick;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class FyersAdapter implements BrokerAdapter {

    @Override
    public String vendorCode() {
        return "FYERS";
    }

    @Override
    public void authenticate(BrokerCredentials credentials) {
    }

    @Override
    public BrokerOrderResponse placeOrder(BrokerOrderRequest request) {
        return new BrokerOrderResponse(UUID.randomUUID(), "ACCEPTED", request.symbol(), request.quantity());
    }

    @Override
    public BrokerOrderResponse modifyOrder(UUID brokerOrderId, BrokerOrderRequest request) {
        return new BrokerOrderResponse(brokerOrderId, "MODIFIED", request.symbol(), request.quantity());
    }

    @Override
    public void cancelOrder(UUID brokerOrderId) {
    }

    @Override
    public List<BrokerPosition> getPositions() {
        return Collections.emptyList();
    }

    @Override
    public List<BrokerOrderResponse> getOrders() {
        return Collections.emptyList();
    }

    @Override
    public void streamTicks(List<String> symbols, Consumer<BrokerTick> onTick) {
    }
}
