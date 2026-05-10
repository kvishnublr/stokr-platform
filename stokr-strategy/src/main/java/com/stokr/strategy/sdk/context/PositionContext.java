package com.stokr.strategy.sdk.context;

import java.math.BigDecimal;

public record PositionContext(
        String symbol,
        BigDecimal quantity,
        BigDecimal avgPrice,
        java.time.Instant lastUpdate
) {
}
