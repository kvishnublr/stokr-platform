package com.stokr.marketdata;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final Universe universe;

    @GetMapping("/candles/{symbol}")
    public ResponseEntity<List<Candle>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5min") String interval,
            @RequestParam(defaultValue = "100") int count) {
        return ResponseEntity.ok(marketDataService.getCandles(symbol, interval, count));
    }

    @GetMapping("/ltp/{symbol}")
    public ResponseEntity<Map<String, BigDecimal>> getLtp(@PathVariable String symbol) {
        return ResponseEntity.ok(Map.of("ltp", marketDataService.getLtp(symbol)));
    }

    @GetMapping("/universe")
    public ResponseEntity<List<String>> getUniverse() {
        return ResponseEntity.ok(universe.getSymbols());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> marketStatus() {
        return ResponseEntity.ok(Map.of(
                "isOpen", marketDataService.isMarketOpen()
        ));
    }
}
