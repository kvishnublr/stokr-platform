package com.stokr.intraday.controller;

import com.stokr.intraday.domain.IndexSignal;
import com.stokr.intraday.service.IndexHuntService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * INDEX HUNT REST API
 * Endpoints for signal detection, retrieval, execution tracking
 */
@RestController
@RequestMapping("/api/index-hunt")
@Slf4j
public class IndexHuntController {

    private final IndexHuntService indexHuntService;

    public IndexHuntController(IndexHuntService indexHuntService) {
        this.indexHuntService = indexHuntService;
    }

    /**
     * Run INDEX HUNT detection for both NIFTY and BANKNIFTY
     * POST /api/index-hunt/detect
     */
    @PostMapping("/detect")
    public ResponseEntity<DetectionResponse> runDetection() {
        log.info("INDEX_HUNT.api_detect_request");

        try {
            List<IndexSignal> signals = indexHuntService.runFullDetection();

            DetectionResponse response = DetectionResponse.builder()
                    .success(true)
                    .signalsDetected(signals.size())
                    .signals(signals)
                    .timestamp(System.currentTimeMillis())
                    .build();

            log.info("INDEX_HUNT.api_detect_response signals_found={}", signals.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("INDEX_HUNT.api_detect_error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all active pending signals
     * GET /api/index-hunt/signals/active
     */
    @GetMapping("/signals/active")
    public ResponseEntity<List<IndexSignal>> getActivePendingSignals() {
        List<IndexSignal> signals = indexHuntService.getActivePendingSignals();
        return ResponseEntity.ok(signals);
    }

    /**
     * Get signals for specific index
     * GET /api/index-hunt/signals/{indexName}
     */
    @GetMapping("/signals/{indexName}")
    public ResponseEntity<List<IndexSignal>> getSignalsForIndex(@PathVariable String indexName) {
        List<IndexSignal> signals = indexHuntService.getSignalsForIndex(indexName);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get high-quality signals (quality >= 76)
     * GET /api/index-hunt/signals/premium
     */
    @GetMapping("/signals/premium")
    public ResponseEntity<List<IndexSignal>> getPremiumSignals() {
        List<IndexSignal> signals = indexHuntService.getHighQualitySignals(BigDecimal.valueOf(76));
        return ResponseEntity.ok(signals);
    }

    /**
     * Get recent signals for index (last N hours)
     * GET /api/index-hunt/signals/{indexName}/recent?hours=4
     */
    @GetMapping("/signals/{indexName}/recent")
    public ResponseEntity<List<IndexSignal>> getRecentSignals(
            @PathVariable String indexName,
            @RequestParam(defaultValue = "4") int hours) {
        List<IndexSignal> signals = indexHuntService.getRecentSignals(indexName, hours);
        return ResponseEntity.ok(signals);
    }

    /**
     * Mark signal as filled (executed)
     * POST /api/index-hunt/signals/{signalId}/fill
     */
    @PostMapping("/signals/{signalId}/fill")
    public ResponseEntity<Map<String, Object>> markSignalFilled(
            @PathVariable Long signalId,
            @RequestBody FillRequest request) {
        try {
            indexHuntService.markSignalFilled(signalId, request.actualEntryPremium);

            Map<String, Object> response = Map.of(
                    "success", true,
                    "signalId", signalId,
                    "entryPremium", request.actualEntryPremium,
                    "timestamp", System.currentTimeMillis()
            );

            log.info("INDEX_HUNT.api_signal_filled signal_id={} entry_premium={}",
                    signalId, request.actualEntryPremium);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("INDEX_HUNT.api_signal_fill_error signal_id={}", signalId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Mark signal as closed (T1 or SL hit)
     * POST /api/index-hunt/signals/{signalId}/close
     */
    @PostMapping("/signals/{signalId}/close")
    public ResponseEntity<Map<String, Object>> markSignalClosed(
            @PathVariable Long signalId,
            @RequestBody CloseRequest request) {
        try {
            indexHuntService.markSignalClosed(
                    signalId,
                    request.actualExitPremium,
                    request.outcomeType,
                    request.totalPnL
            );

            Map<String, Object> response = Map.of(
                    "success", true,
                    "signalId", signalId,
                    "outcome", request.outcomeType,
                    "pnl", request.totalPnL,
                    "timestamp", System.currentTimeMillis()
            );

            log.info("INDEX_HUNT.api_signal_closed signal_id={} outcome={} pnl={}",
                    signalId, request.outcomeType, request.totalPnL);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("INDEX_HUNT.api_signal_close_error signal_id={}", signalId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get daily statistics
     * GET /api/index-hunt/stats/today
     */
    @GetMapping("/stats/today")
    public ResponseEntity<StatsResponse> getDailyStats() {
        IndexHuntService.IndexHuntDailyStats stats = indexHuntService.getDailyStats();

        StatsResponse response = StatsResponse.builder()
                .totalTrades(stats.totalTrades)
                .wins(stats.wins)
                .losses(stats.losses)
                .winRate(String.format("%.1f%%", stats.winRate))
                .totalPnL(stats.totalPnL)
                .timestamp(System.currentTimeMillis())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Health check
     * GET /api/index-hunt/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "INDEX_HUNT",
                "version", "1.0.0"
        ));
    }

    // DTOs

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Builder
    public static class DetectionResponse {
        private boolean success;
        private int signalsDetected;
        private List<IndexSignal> signals;
        private long timestamp;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class FillRequest {
        public BigDecimal actualEntryPremium;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class CloseRequest {
        public BigDecimal actualExitPremium;
        public String outcomeType;  // "WIN" or "LOSS"
        public BigDecimal totalPnL;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Builder
    public static class StatsResponse {
        private int totalTrades;
        private long wins;
        private long losses;
        private String winRate;
        private BigDecimal totalPnL;
        private long timestamp;
    }
}
