package com.stokr.user.broker.historical;

public interface BrokerHistoricalDataAdapter {
    String brokerCode();
    BrokerHistoricalCapability capability();
    HistoricalFetchResult fetch(HistoricalFetchRequest request);
}
