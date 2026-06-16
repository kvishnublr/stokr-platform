package com.stokr.strategy.sdk.context;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Read-only indicator bundle at {@code asOf}.
 */
public record IndicatorContext(
        java.time.Instant asOf,
        Map<String, BigDecimal> values
) {
}
