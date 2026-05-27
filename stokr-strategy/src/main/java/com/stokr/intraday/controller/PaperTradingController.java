package com.stokr.intraday.controller;

import com.stokr.intraday.domain.IndexSignal;
import com.stokr.intraday.service.IndexHuntService;
import com.stokr.intraday.service.PaperTradingExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Paper Trading REST API for INDEX HUNT
 * Allows manual execution of signals in simulation mode
 */
@RestController
@RequestMapping("/api/paper-trading")
@Slf4j
public class PaperTradingController {

    private final IndexHuntService indexHuntService;
    private final PaperTradingExecutor paperTradingExecutor;

    public PaperTradingController(IndexHuntService indexHuntService,
                                 PaperTradingExecutor paperTradingExecutor) {
        this.indexHuntService = indexHuntService;
        this.paperTradingExecutor = paperTradingExecutor;
    }

    /**
     * Execute a pending signal in paper trading
     * POST /api/paper-trading/execute/{signalId}
     */
    @PostMapping("/execute/{signalId}")
    public ResponseEntity<ExecuteResponse> executeSignal(@PathVariable Long signalId) {
        try {
            // Find the signal
            List<IndexSignal> signals = indexHuntService.getActivePendingSignals();
            IndexSignal signal = signals.stream()
                    .filter(s -> s.getSignalId().equals(signalId))
                    .findFirst()
                    .orElse(null);

            if (signal == null) {
                return ResponseEntity.badRequest().body(
                        ExecuteResponse.error("Signal not found")
                );
            }

            // Execute in paper trading
            PaperTradingExecutor.PaperTradeResult result = paperTradingExecutor.executeSignal(signal);

            ExecuteResponse response = result.success ?
                    ExecuteResponse.success(signalId, result.message) :
                    ExecuteResponse.error(result.message);

            log.info("PAPER_TRADING.api_execute signal_id={} success={}", signalId, result.success);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("PAPER_TRADING.api_execute_error signal_id={} error={}", signalId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    ExecuteResponse.error("Execution error: " + e.getMessage())
            );
        }
    }

    /**
     * Get all active paper trades
     * GET /api/paper-trading/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<PaperTradingExecutor.PaperTrade>> getActiveTrades() {
        List<PaperTradingExecutor.PaperTrade> trades = paperTradingExecutor.getActiveTrades();
        return ResponseEntity.ok(trades);
    }

    /**
     * Monitor active trades and check for T1/SL hits
     * This should be called every 5-30 seconds by a scheduler
     *
     * POST /api/paper-trading/monitor
     * Body: { "prices": { "NIFTY": 24100.50, "BANKNIFTY": 48350.75 } }
     */
    @PostMapping("/monitor")
    public ResponseEntity<MonitorResponse> monitorTrades(@RequestBody MonitorRequest request) {
        try {
            paperTradingExecutor.monitorActiveTrades(request.prices);

            List<PaperTradingExecutor.PaperTrade> active = paperTradingExecutor.getActiveTrades();

            MonitorResponse response = new MonitorResponse();
            response.success = true;
            response.activeTrades = active.size();
            response.timestamp = System.currentTimeMillis();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("PAPER_TRADING.monitor_error error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get paper trading statistics
     * GET /api/paper-trading/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<PaperTradingExecutor.PaperTradingStats> getStats() {
        PaperTradingExecutor.PaperTradingStats stats = paperTradingExecutor.getStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Reset paper trading (clear all trades - for testing)
     * POST /api/paper-trading/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetPaperTrading() {
        paperTradingExecutor.resetPaperTrading();

        log.warn("PAPER_TRADING.api_reset_all_trades");

        return ResponseEntity.ok(Map.of(
                "success", "true",
                "message", "All paper trades cleared"
        ));
    }

    /**
     * Get next pending signals ready for execution
     * GET /api/paper-trading/next-signals?limit=5
     */
    @GetMapping("/next-signals")
    public ResponseEntity<List<IndexSignal>> getNextSignals(
            @RequestParam(defaultValue = "5") int limit) {
        List<IndexSignal> signals = indexHuntService.getHighQualitySignals(
                BigDecimal.valueOf(68)
        );

        // Return top N signals
        if (signals.size() > limit) {
            signals = signals.subList(0, limit);
        }

        return ResponseEntity.ok(signals);
    }

    // ===== DTOs =====

    @lombok.Data
    public static class ExecuteResponse {
        public boolean success;
        public Long signalId;
        public String message;
        public long timestamp;

        public static ExecuteResponse success(Long signalId, String message) {
            ExecuteResponse response = new ExecuteResponse();
            response.success = true;
            response.signalId = signalId;
            response.message = message;
            response.timestamp = System.currentTimeMillis();
            return response;
        }

        public static ExecuteResponse error(String message) {
            ExecuteResponse response = new ExecuteResponse();
            response.success = false;
            response.message = message;
            response.timestamp = System.currentTimeMillis();
            return response;
        }
    }

    @lombok.Data
    public static class MonitorRequest {
        public Map<String, BigDecimal> prices;
    }

    @lombok.Data
    public static class MonitorResponse {
        public boolean success;
        public int activeTrades;
        public long timestamp;
    }
}
