package com.stokr.broker.api;

import com.stokr.broker.model.BrokerCredentials;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.model.BrokerTick;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Broker-neutral adapter contract. Implementations wrap specific vendor APIs.
 */
public interface BrokerAdapter {

    String vendorCode();

    void authenticate(BrokerCredentials credentials);

    BrokerOrderResponse placeOrder(BrokerOrderRequest request);

    BrokerOrderResponse modifyOrder(UUID brokerOrderId, BrokerOrderRequest request);

    void cancelOrder(UUID brokerOrderId);

    List<BrokerPosition> getPositions();

    List<BrokerOrderResponse> getOrders();

    void streamTicks(List<String> symbols, Consumer<BrokerTick> onTick);
}
