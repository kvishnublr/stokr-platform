package com.stokr.intraday.controller;

import com.stokr.intraday.domain.FuturesSignal;
import com.stokr.intraday.service.FuturesSignalService;
import com.stokr.intraday.service.FuturesTradingExecutor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Futures Trading REST API for S3 and S7
 */
@RestController
@RequestMapping("/api/futures-trading")
@Slf4j
public class FuturesSignalController {

    private final FuturesSignalService futuresSignalService;
    private final FuturesTradingExecutor futuresTradingExecutor;

    public FuturesSignalController(FuturesSignalService futuresSignalService,
                                  FuturesTradingExecutor futuresTradingExecutor) {
        this.futuresSignalService = futuresSignalService;
        this.futuresTradingExecutor = futuresTradingExecutor;
    }

    /**
     * Run detection for S3 and S7
     * POST /api/futures-trading/detect
     */
    @PostMapping("/detect")
    public ResponseEntity<DetectionResponse> detectSignals() {
        try {
            List<FuturesSignal> signals = futuresSignalService.runFullDetection();

            DetectionResponse response = new DetectionResponse();
            response.success = true;
            response.signalsDetected = signals.size();
            response.signals = signals;
            response.timestamp = System.currentTimeMillis();

            log.info("FUTURES.api_detect signals={}", signals.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("FUTURES.api_detect_error error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get active pending signals
     * GET /api/futures-trading/signals/active
     */
    @GetMapping("/signals/active")
    public ResponseEntity<List<FuturesSignal>> getActiveSignals() {
        List<FuturesSignal> signals = futuresSignalService.getActivePendingSignals();
        return ResponseEntity.ok(signals);
    }

    /**
     * Get signals by strategy
     * GET /api/futures-trading/signals/{strategy}
     */
    @GetMapping("/signals/{strategy}")
    public ResponseEntity<List<FuturesSignal>> getSignalsByStrategy(@PathVariable String strategy) {
        List<FuturesSignal> signals = futuresSignalService.getSignalsByStrategy(strategy);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get high quality signals
     * GET /api/futures-trading/signals/quality/premium
     */
    @GetMapping("/signals/quality/premium")
    public ResponseEntity<List<FuturesSignal>> getPremiumQualitySignals() {
        List<FuturesSignal> signals = futuresSignalService.getHighQualitySignals(BigDecimal.valueOf(80));
        return ResponseEntity.ok(signals);
    }

    /**
     * Execute a futures signal
     * POST /api/futures-trading/execute/{signalId}
     */
    @PostMapping("/execute/{signalId}")
    public ResponseEntity<ExecuteResponse> executeSignal(@PathVariable Long signalId) {
        try {
            List<FuturesSignal> signals = futuresSignalService.getActivePendingSignals();
            FuturesSignal signal = signals.stream()
                    .filter(s -> s.getSignalId().equals(signalId))
                    .findFirst()
                    .orElse(null);

            if (signal == null) {
                return ResponseEntity.badRequest().body(
                        ExecuteResponse.error("Signal not found")
                );
            }

            // Execute in paper trading
            FuturesTradingExecutor.FutureTradeResult result = futuresTradingExecutor.executeSignal(signal);

            ExecuteResponse response = result.success ?
                    ExecuteResponse.success(signalId, result.message) :
                    ExecuteResponse.error(result.message);

            log.info("FUTURES.api_execute signal_id={} success={}", signalId, result.success);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("FUTURES.api_execute_error signal_id={} error={}", signalId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    ExecuteResponse.error("Execution error: " + e.getMessage())
            );
        }
    }

    /**
     * Get active trades
     * GET /api/futures-trading/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<FuturesTradingExecutor.FuturesTrade>> getActiveTrades() {
        List<FuturesTradingExecutor.FuturesTrade> trades = futuresTradingExecutor.getActiveTrades();
        return ResponseEntity.ok(trades);
    }

    /**
     * Monitor active trades
     * POST /api/futures-trading/monitor
     */
    @PostMapping("/monitor")
    public ResponseEntity<MonitorResponse> monitorTrades(@RequestBody MonitorRequest request) {
        try {
            futuresTradingExecutor.monitorActiveTrades(request.prices);

            List<FuturesTradingExecutor.FuturesTrade> active = futuresTradingExecutor.getActiveTrades();

            MonitorResponse response = new MonitorResponse();
            response.success = true;
            response.activeTrades = active.size();
            response.timestamp = System.currentTimeMillis();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("FUTURES.monitor_error error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get trading statistics
     * GET /api/futures-trading/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<FuturesTradingExecutor.FuturesTradingStats> getStats() {
        FuturesTradingExecutor.FuturesTradingStats stats = futuresTradingExecutor.getStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Reset all trades (testing)
     * POST /api/futures-trading/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetTrades() {
        futuresTradingExecutor.resetTrades();
        log.warn("FUTURES.api_reset_all");
        return ResponseEntity.ok(Map.of("success", "true", "message", "All futures trades cleared"));
    }

    // ===== DTOs =====

    @Data
    public static class DetectionResponse {
        public boolean success;
        public int signalsDetected;
        public List<FuturesSignal> signals;
        public long timestamp;
    }

    @Data
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

    @Data
    public static class MonitorRequest {
        public Map<String, BigDecimal> prices;
    }

    @Data
    public static class MonitorResponse {
        public boolean success;
        public int activeTrades;
        public long timestamp;
    }
}
