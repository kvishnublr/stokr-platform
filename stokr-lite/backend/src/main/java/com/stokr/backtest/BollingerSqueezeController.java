package com.stokr.backtest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BollingerSqueezeController {

    private final BollingerSqueezeService service;

    @GetMapping("/squeeze")
    public ResponseEntity<?> run(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam(defaultValue = "2026-04-01") LocalDate start,
            @RequestParam(defaultValue = "2026-06-29") LocalDate end) {
        try {
            var r = service.runBacktest(symbol, start, end);
            if (r.error != null) return ResponseEntity.badRequest().body(Map.of("error", r.error));
            return ResponseEntity.ok(format(r));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> format(BollingerSqueezeService.BacktestReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", r.symbol);
        m.put("period", r.startDate + " to " + r.endDate);
        m.put("days", r.totalDays);
        m.put("strategy", "Bollinger Squeeze Breakout");
        m.put("params", "BB(20,2) squeeze=50period min vol=1.5x stop=0.5% maxHold=5");
        m.put("trades", r.totalTrades);
        m.put("perDay", String.format("%.1f", r.anomaliesPerDay));
        m.put("winRate", String.format("%.1f%%", r.winRate * 100));
        m.put("grossPnL", String.format("₹%.0f", r.totalGrossPnL));
        m.put("brokerage", String.format("₹%.0f", r.totalBrokerage));
        m.put("netPnL", String.format("₹%.0f", r.totalNetPnL));
        m.put("avgPerTrade", String.format("₹%.0f", r.avgNetPerTrade));

        for (var row : r.typeResults) {
            if ("SUMMARY".equals(row.type)) continue;
            m.put(row.type, row.count);
        }

        return m;
    }
}
