package com.stokr.broker.registry;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.common.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BrokerAdapterRegistry {

    private final Map<String, BrokerAdapter> byVendor;

    public BrokerAdapterRegistry(List<BrokerAdapter> adapters) {
        this.byVendor = adapters.stream()
                .collect(Collectors.toMap(BrokerAdapter::vendorCode, Function.identity()));
    }

    public BrokerAdapter get(String vendorCode) {
        BrokerAdapter a = byVendor.get(vendorCode.toUpperCase());
        if (a == null) {
            throw new NotFoundException("Unknown broker vendor: " + vendorCode);
        }
        return a;
    }
}
