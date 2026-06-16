package com.stokr.user.broker.historical;

import java.math.BigDecimal;
import java.time.Instant;

public record HistoricalCandlePoint(
        Instant openTime,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal volume
) {
}
