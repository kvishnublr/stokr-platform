package com.stokr.user.broker.historical;

import java.util.List;

public record HistoricalFetchResult(
        boolean success,
        String code,
        String detail,
        List<HistoricalCandlePoint> candles
) {
    public static HistoricalFetchResult ok(List<HistoricalCandlePoint> candles) {
        return new HistoricalFetchResult(true, "OK", "Fetched", candles);
    }

    public static HistoricalFetchResult fail(String code, String detail) {
        return new HistoricalFetchResult(false, code, detail, List.of());
    }
}
