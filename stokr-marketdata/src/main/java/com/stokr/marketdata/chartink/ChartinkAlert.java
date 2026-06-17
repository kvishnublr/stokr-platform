package com.stokr.marketdata.chartink;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ChartinkAlert(
        String scanName,
        String alertName,
        String scanUrl,
        Instant triggeredAt,
        String triggeredAtRaw,
        List<String> stocks,
        List<BigDecimal> triggerPrices,
        String rawPayload
) {
    public record StockAlert(
            String symbol,
            BigDecimal triggerPrice,
            String scanName,
            Instant triggeredAt
    ) {}
}
