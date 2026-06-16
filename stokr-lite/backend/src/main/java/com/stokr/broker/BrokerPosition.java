package com.stokr.broker;

import java.math.BigDecimal;

public record BrokerPosition(
        String symbol,
        String exchange,
        int quantity,
        BigDecimal avgPrice,
        BigDecimal lastPrice,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        String productType
) {}
