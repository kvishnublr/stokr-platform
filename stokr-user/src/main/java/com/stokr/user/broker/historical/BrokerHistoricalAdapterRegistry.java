package com.stokr.user.broker.historical;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BrokerHistoricalAdapterRegistry {

    private final Map<String, BrokerHistoricalDataAdapter> byCode;

    public BrokerHistoricalAdapterRegistry(List<BrokerHistoricalDataAdapter> adapters) {
        Map<String, BrokerHistoricalDataAdapter> map = new LinkedHashMap<>();
        for (BrokerHistoricalDataAdapter adapter : adapters) {
            map.put(adapter.brokerCode().toUpperCase(Locale.ROOT), adapter);
        }
        this.byCode = Map.copyOf(map);
    }

    public BrokerHistoricalDataAdapter require(String brokerCode) {
        if (brokerCode == null || brokerCode.isBlank()) {
            return new UnsupportedHistoricalAdapter("UNKNOWN", "Broker source is required");
        }
        return byCode.getOrDefault(
                brokerCode.trim().toUpperCase(Locale.ROOT),
                new UnsupportedHistoricalAdapter(brokerCode.trim().toUpperCase(Locale.ROOT), "No adapter registered")
        );
    }

    public Map<String, BrokerHistoricalCapability> capabilityMatrix() {
        Map<String, BrokerHistoricalCapability> m = new LinkedHashMap<>();
        for (var e : byCode.entrySet()) {
            m.put(e.getKey(), e.getValue().capability());
        }
        return m;
    }
}
