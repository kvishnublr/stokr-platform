package com.stokr.user.broker.historical;

import java.util.List;

public record BrokerHistoricalCapability(
        boolean implemented,
        List<String> supportedTimeframes,
        int maxLookbackDays,
        Integer rateLimitPerMinute,
        String state,
        String detail
) {
}
