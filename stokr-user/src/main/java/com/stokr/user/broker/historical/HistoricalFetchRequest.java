package com.stokr.user.broker.historical;

import java.time.Instant;

public record HistoricalFetchRequest(
        String symbol,
        String timeframe,
        Instant rangeStart,
        Instant rangeEnd
) {
}
