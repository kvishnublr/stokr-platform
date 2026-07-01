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
public class SuddenMoveBacktestController {

    private final SuddenMoveBacktestService backtestService;

    @GetMapping("/sudden-move")
    public ResponseEntity<?> runSuddenMoveBacktest(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam(defaultValue = "2026-04-01") LocalDate startDate,
            @RequestParam(defaultValue = "2026-06-29") LocalDate endDate) {
        try {
            var report = backtestService.runBacktest(symbol, startDate, endDate);
            if (report.error != null) {
                return ResponseEntity.badRequest().body(Map.of("error", report.error));
            }
            return ResponseEntity.ok(formatReport(report));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> formatReport(SuddenMoveBacktestService.BacktestReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", r.symbol);
        m.put("period", r.startDate + " to " + r.endDate);
        m.put("tradingDays", r.totalDays);
        m.put("totalCandles", r.totalCandles);
        m.put("totalSignals", r.totalAnomalies);
        m.put("signalsPerDay", String.format("%.1f", r.anomaliesPerDay));

        // P&L summary
        m.put("strategy", "Sudden-Move Candle Detection");
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
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("trades", row.count);
            tr.put("winRate", String.format("%.1f%%", row.winRate * 100));
            tr.put("avgReturn", String.format("%.4f", row.avgReturn));
            tr.put("grossPnL", String.format("₹%.0f", row.grossPnL));
            tr.put("netPnL", String.format("₹%.0f", row.netPnL));
            tr.put("brokerage", String.format("₹%.0f", row.brokerage));
            tr.put("bestTrade", String.format("₹%.0f", row.bestTrade));
            tr.put("worstTrade", String.format("₹%.0f", row.worstTrade));
            m.put("type_" + row.type, tr);
        }

        return m;
    }
}
