package com.stokr.broker;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BrokerRegistry {

    private final Map<String, BrokerAdapter> adapters;

    public BrokerRegistry(List<BrokerAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(BrokerAdapter::getBrokerName, Function.identity()));
    }

    public BrokerAdapter getAdapter(String brokerName) {
        BrokerAdapter adapter = adapters.get(brokerName.toUpperCase());
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported broker: " + brokerName);
        }
        return adapter;
    }

    public List<String> getSupportedBrokers() {
        return List.copyOf(adapters.keySet());
    }
}
