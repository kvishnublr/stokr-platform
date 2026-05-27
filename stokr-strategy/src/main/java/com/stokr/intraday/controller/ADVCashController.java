package com.stokr.intraday.controller;

import com.stokr.intraday.domain.EquitySignal;
import com.stokr.intraday.service.ADVCashService;
import com.stokr.intraday.service.EquityCashTradingExecutor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ADV CASH REST API - Equity Cash Strategy
 */
@RestController
@RequestMapping("/api/adv-cash")
@Slf4j
public class ADVCashController {

    private final ADVCashService advCashService;
    private final EquityCashTradingExecutor equityCashTradingExecutor;

    public ADVCashController(ADVCashService advCashService,
                           EquityCashTradingExecutor equityCashTradingExecutor) {
        this.advCashService = advCashService;
        this.equityCashTradingExecutor = equityCashTradingExecutor;
    }

    /**
     * Run ADV_CASH detection
     * POST /api/adv-cash/detect
     */
    @PostMapping("/detect")
    public ResponseEntity<DetectionResponse> detectSignals() {
        try {
            List<EquitySignal> signals = advCashService.runFullDetection();

            DetectionResponse response = new DetectionResponse();
            response.success = true;
            response.signalsDetected = signals.size();
            response.signals = signals;
            response.timestamp = System.currentTimeMillis();

            log.info("ADV_CASH.api_detect signals={}", signals.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ADV_CASH.api_detect_error error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get active pending signals
     * GET /api/adv-cash/signals/active
     */
    @GetMapping("/signals/active")
    public ResponseEntity<List<EquitySignal>> getActiveSignals() {
        List<EquitySignal> signals = advCashService.getActivePendingSignals();
        return ResponseEntity.ok(signals);
    }

    /**
     * Get high confidence signals (3-TF alignment)
     * GET /api/adv-cash/signals/confidence/high
     */
    @GetMapping("/signals/confidence/high")
    public ResponseEntity<List<EquitySignal>> getHighConfidenceSignals() {
        List<EquitySignal> signals = advCashService.getHighConfidenceSignals();
        return ResponseEntity.ok(signals);
    }

    /**
     * Get signals by sector
     * GET /api/adv-cash/signals/sector/{sector}
     */
    @GetMapping("/signals/sector/{sector}")
    public ResponseEntity<List<EquitySignal>> getSignalsBySector(@PathVariable String sector) {
        List<EquitySignal> signals = advCashService.getSignalsBySector(sector);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get high quality signals
     * GET /api/adv-cash/signals/quality/high
     */
    @GetMapping("/signals/quality/high")
    public ResponseEntity<List<EquitySignal>> getHighQualitySignals() {
        List<EquitySignal> signals = advCashService.getHighQualitySignals(BigDecimal.valueOf(75));
        return ResponseEntity.ok(signals);
    }

    /**
     * Execute a signal in paper trading
     * POST /api/adv-cash/execute/{signalId}
     */
    @PostMapping("/execute/{signalId}")
    public ResponseEntity<ExecuteResponse> executeSignal(@PathVariable Long signalId) {
        try {
            List<EquitySignal> signals = advCashService.getActivePendingSignals();
            EquitySignal signal = signals.stream()
                    .filter(s -> s.getSignalId().equals(signalId))
                    .findFirst()
                    .orElse(null);

            if (signal == null) {
                return ResponseEntity.badRequest().body(
                        ExecuteResponse.error("Signal not found")
                );
            }

            // Execute in paper trading
            EquityCashTradingExecutor.EquityCashTradeResult result = equityCashTradingExecutor.executeSignal(signal);

            ExecuteResponse response = result.success ?
                    ExecuteResponse.success(signalId, result.message) :
                    ExecuteResponse.error(result.message);

            log.info("ADV_CASH.api_execute signal_id={} success={}", signalId, result.success);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ADV_CASH.api_execute_error signal_id={} error={}", signalId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    ExecuteResponse.error("Execution error: " + e.getMessage())
            );
        }
    }

    /**
     * Get active trades
     * GET /api/adv-cash/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<EquityCashTradingExecutor.EquityCashTrade>> getActiveTrades() {
        List<EquityCashTradingExecutor.EquityCashTrade> trades = equityCashTradingExecutor.getActiveTrades();
        return ResponseEntity.ok(trades);
    }

    /**
     * Monitor active trades
     * POST /api/adv-cash/monitor
     */
    @PostMapping("/monitor")
    public ResponseEntity<MonitorResponse> monitorTrades(@RequestBody MonitorRequest request) {
        try {
            equityCashTradingExecutor.monitorActiveTrades(request.prices);

            List<EquityCashTradingExecutor.EquityCashTrade> active = equityCashTradingExecutor.getActiveTrades();

            MonitorResponse response = new MonitorResponse();
            response.success = true;
            response.activeTrades = active.size();
            response.timestamp = System.currentTimeMillis();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ADV_CASH.monitor_error error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get trading statistics
     * GET /api/adv-cash/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<EquityCashTradingExecutor.EquityCashTradingStats> getStats() {
        EquityCashTradingExecutor.EquityCashTradingStats stats = equityCashTradingExecutor.getStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Reset all trades (testing only)
     * POST /api/adv-cash/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetTrades() {
        equityCashTradingExecutor.resetTrades();
        log.warn("ADV_CASH.api_reset_all");
        return ResponseEntity.ok(Map.of("success", "true", "message", "All ADV_CASH trades cleared"));
    }

    // ===== DTOs =====

    @Data
    public static class DetectionResponse {
        public boolean success;
        public int signalsDetected;
        public List<EquitySignal> signals;
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
