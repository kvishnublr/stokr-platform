package com.stokr.user.broker.historical;

import java.util.List;

public class UnsupportedHistoricalAdapter implements BrokerHistoricalDataAdapter {

    private final String code;
    private final String detail;

    public UnsupportedHistoricalAdapter(String code, String detail) {
        this.code = code;
        this.detail = detail;
    }

    @Override
    public String brokerCode() {
        return code;
    }

    @Override
    public BrokerHistoricalCapability capability() {
        return new BrokerHistoricalCapability(
                false,
                List.of(),
                0,
                null,
                "NOT_IMPLEMENTED",
                detail
        );
    }

    @Override
    public HistoricalFetchResult fetch(HistoricalFetchRequest request) {
        return HistoricalFetchResult.fail("NOT_IMPLEMENTED", detail);
    }
}
