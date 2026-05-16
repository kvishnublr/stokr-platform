package com.stokr.user.broker.historical;

import java.util.List;

public record HistoricalFetchResult(
        boolean success,
        String code,
        String detail,
        List<HistoricalCandlePoint> candles,
        String authSource
) {
    public static HistoricalFetchResult ok(List<HistoricalCandlePoint> candles) {
        return new HistoricalFetchResult(true, "OK", "Fetched", candles, "UNKNOWN");
    }

    public static HistoricalFetchResult okWithSource(List<HistoricalCandlePoint> candles, String authSource) {
        return new HistoricalFetchResult(true, "OK", "Fetched", candles, authSource == null ? "UNKNOWN" : authSource);
    }

    public static HistoricalFetchResult fail(String code, String detail) {
        return new HistoricalFetchResult(false, code, detail, List.of(), "UNKNOWN");
    }

    public static HistoricalFetchResult failWithSource(String code, String detail, String authSource) {
        return new HistoricalFetchResult(false, code, detail, List.of(), authSource == null ? "UNKNOWN" : authSource);
    }

    public static HistoricalFetchResult failWithCandles(String code, String detail, List<HistoricalCandlePoint> candles) {
        return new HistoricalFetchResult(false, code, detail, candles == null ? List.of() : candles, "UNKNOWN");
    }

    public static HistoricalFetchResult failWithCandlesAndSource(String code, String detail, List<HistoricalCandlePoint> candles, String authSource) {
        return new HistoricalFetchResult(
                false,
                code,
                detail,
                candles == null ? List.of() : candles,
                authSource == null ? "UNKNOWN" : authSource
        );
    }
}
