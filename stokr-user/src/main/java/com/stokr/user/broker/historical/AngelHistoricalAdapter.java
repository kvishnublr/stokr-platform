package com.stokr.user.broker.historical;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AngelHistoricalAdapter implements BrokerHistoricalDataAdapter {
    @Override
    public String brokerCode() {
        return "ANGEL";
    }

    @Override
    public BrokerHistoricalCapability capability() {
        return new BrokerHistoricalCapability(false, List.of(), 0, null, "NOT_IMPLEMENTED", "Angel historical bridge not wired in this build");
    }

    @Override
    public HistoricalFetchResult fetch(HistoricalFetchRequest request) {
        return HistoricalFetchResult.fail("NOT_IMPLEMENTED", "Angel historical bridge not wired in this build");
    }
}
