package com.stokr.marketdata.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy {@code /api/market/*} paths — prefer {@code /api/trader/terminal/market/*} for trader UI; admin/ops tools may still use marketdata module APIs.
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketLegacyController {

    private final MarketDataQueryService marketDataQueryService;

    @GetMapping("/watch")
    public ApiResponse<List<Map<String, Object>>> watch() {
        return ApiResponse.ok(List.of(), CorrelationIdHolder.get());
    }

    @GetMapping("/candles")
    public ApiResponse<List<Map<String, Object>>> candles(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "interval", defaultValue = "5m") String interval,
            @RequestParam(value = "timeframe", required = false) String timeframe,
            @RequestParam(value = "limit", defaultValue = "120") int limit
    ) {
        String tf = timeframe != null && !timeframe.isBlank() ? timeframe : interval;
        int capped = Math.min(Math.max(limit, 1), 500);
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, tf, capped);
        List<Map<String, Object>> out = new ArrayList<>(bars.size());
        for (MarketdataCandle c : bars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", c.getOpenTime() != null ? c.getOpenTime().toEpochMilli() : 0L);
            row.put("ts", row.get("time"));
            row.put("open", bd(c.getOpenPrice()));
            row.put("high", bd(c.getHighPrice()));
            row.put("low", bd(c.getLowPrice()));
            row.put("close", bd(c.getClosePrice()));
            row.put("volume", bd(c.getVolume()));
            out.add(row);
        }
        return ApiResponse.ok(out, CorrelationIdHolder.get());
    }

    private static double bd(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }
}
