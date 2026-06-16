package com.stokr.strategy.context;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record StrategyContext(
        String symbol,
        Instant asOf,
        Map<String, BigDecimal> indicators,
        BigDecimal lastPrice
) {
}
