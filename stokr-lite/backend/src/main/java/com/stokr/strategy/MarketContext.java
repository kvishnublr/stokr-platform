package com.stokr.strategy;

import com.stokr.marketdata.Candle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Market context passed to strategy plugins for evaluation.
 */
public record MarketContext(
        String symbol,
        List<Candle> candles,
        BigDecimal currentPrice,
        BigDecimal vwap,
        Map<String, BigDecimal> indicators
) {
    public Candle getLatestCandle() {
        return candles.isEmpty() ? null : candles.get(candles.size() - 1);
    }

    public Candle getPreviousCandle() {
        return candles.size() < 2 ? null : candles.get(candles.size() - 2);
    }

    public BigDecimal getDayHigh() {
        return candles.stream()
                .map(Candle::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    public BigDecimal getDayLow() {
        return candles.stream()
                .map(Candle::low)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    public BigDecimal getOpenPrice() {
        return candles.isEmpty() ? BigDecimal.ZERO : candles.get(0).open();
    }
}
