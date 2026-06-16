package com.stokr.broker.model;

import java.math.BigDecimal;
import java.time.Instant;

public record BrokerTick(
        String symbol,
        BigDecimal lastPrice,
        Instant timestamp
) {
}
