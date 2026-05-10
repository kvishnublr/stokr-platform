package com.stokr.broker.model;

import java.math.BigDecimal;

public record BrokerPosition(
        String symbol,
        BigDecimal quantity,
        BigDecimal averagePrice
) {
}
