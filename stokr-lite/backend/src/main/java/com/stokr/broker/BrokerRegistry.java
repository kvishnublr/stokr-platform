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
                .collect(Collectors.toMap(
                    adapter -> adapter.getBrokerName().replace(" ", "").replace("_", "").toUpperCase(),
                    Function.identity(),
                    (a, b) -> a
                ));
    }

    public BrokerAdapter getAdapter(String brokerName) {
        if (brokerName == null) throw new IllegalArgumentException("Broker name cannot be null");
        String norm = brokerName.replace(" ", "").replace("_", "").toUpperCase();
        BrokerAdapter adapter = adapters.get(norm);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported broker: " + brokerName);
        }
        return adapter;
    }

    public List<String> getSupportedBrokers() {
        return adapters.keySet().stream()
                .filter(name -> !"PAPER".equals(name))
                .sorted()
                .toList();
    }
}
