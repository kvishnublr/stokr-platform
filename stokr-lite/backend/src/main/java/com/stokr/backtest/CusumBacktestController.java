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
public class CusumBacktestController {

    private final CusumBacktestService service;

    @GetMapping("/cusum")
    public ResponseEntity<?> runCusumBacktest(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam(defaultValue = "2026-04-01") LocalDate startDate,
            @RequestParam(defaultValue = "2026-06-29") LocalDate endDate,
            @RequestParam(defaultValue = "3.0") double h,
            @RequestParam(defaultValue = "0.25") double k,
            @RequestParam(defaultValue = "6") int maxHold,
            @RequestParam(defaultValue = "1.0") double volRatio,
            @RequestParam(defaultValue = "0.006") double stopLoss) {
        try {
            var report = service.runBacktest(symbol, startDate, endDate, h, k, maxHold, volRatio, stopLoss);
            if (report.error != null) {
                return ResponseEntity.badRequest().body(Map.of("error", report.error));
            }
            return ResponseEntity.ok(format(report, h, k, maxHold, volRatio, stopLoss));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> format(CusumBacktestService.BacktestReport r,
                                        double h, double k, int maxHold, double volRatio, double stopLoss) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", r.symbol);
        m.put("period", r.startDate + " to " + r.endDate);
        m.put("tradingDays", r.totalDays);
        m.put("totalCandles", r.totalCandles);

        m.put("strategy", "CUSUM 5-min");
        m.put("params", String.format("k=%.2f h=%.1f hold=%d stop=%.1f%% volRatio=%.1f 10:00-14:30",
            k, h, maxHold, stopLoss * 100, volRatio));
        m.put("capitalPerTrade", "₹12,500");

        m.put("totalTrades", r.totalTrades);
        m.put("wins", r.winCount);
        m.put("losses", r.lossCount);
        m.put("winRate", String.format("%.1f%%", r.winRate * 100));
        m.put("totalGrossPnL", String.format("₹%.0f", r.totalGrossPnL));
        m.put("totalBrokerage", String.format("₹%.0f", r.totalBrokerage));
        m.put("totalNetPnL", String.format("₹%.0f", r.totalNetPnL));
        m.put("avgNetPerTrade", String.format("₹%.0f", r.avgNetPerTrade));

        for (var row : r.typeResults) {
            if ("SUMMARY".equals(row.type)) continue;
            m.put(row.type, row.count);
        }

        return m;
    }
}
