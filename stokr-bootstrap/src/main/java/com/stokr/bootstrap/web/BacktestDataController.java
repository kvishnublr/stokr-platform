package com.stokr.bootstrap.web;

import com.stokr.bootstrap.feed.zerodha.BacktestHistoricalDataLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API for backtest historical data loading and monitoring.
 */
@RestController
@RequestMapping("/api/v1/admin/backtest-data")
@Slf4j
@RequiredArgsConstructor
public class BacktestDataController {

    private final BacktestHistoricalDataLoader historicalDataLoader;

    @PostMapping("/start-load")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> startHistoricalLoad(
            @RequestParam(required = false, defaultValue = "") List<String> symbols) {

        if (historicalDataLoader.isRunning()) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_running",
                    "message", "Historical data load is already in progress",
                    "total_loaded_so_far", historicalDataLoader.getTotalLoaded()
            ));
        }

        historicalDataLoader.startBackgroundHistoricalLoad(symbols);

        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "message", "Historical data load started in background (5-year lookback)",
                "symbols_count", symbols.isEmpty() ? "all_available" : symbols.size(),
                "expected_duration", "30-60 minutes depending on symbol count"
        ));
    }

    @GetMapping("/progress")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getProgress() {
        Map<String, Object> progress = historicalDataLoader.getProgress();

        return ResponseEntity.ok(Map.of(
                "status", progress.get("running").equals(true) ? "loading" : "completed",
                "progress", progress,
                "last_updated", java.time.Instant.now().toString()
        ));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> progress = historicalDataLoader.getProgress();

        boolean running = (boolean) progress.get("running");
        long totalLoaded = (long) progress.get("total_candles_loaded");
        int symbolsCompleted = (int) progress.get("symbols_completed");
        int failedSymbols = (int) progress.get("failed_symbols");

        return ResponseEntity.ok(Map.of(
                "status", running ? "LOADING..." : "COMPLETED",
                "total_candles_loaded", totalLoaded,
                "symbols_completed", symbolsCompleted,
                "failed_symbols", failedSymbols,
                "elapsed_seconds", progress.get("elapsed_seconds"),
                "message", running
                        ? String.format("Loading... %d candles loaded from %d symbols", totalLoaded, symbolsCompleted)
                        : String.format("Load complete. %d candles from %d symbols", totalLoaded, symbolsCompleted)
        ));
    }

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> progress = historicalDataLoader.getProgress();

        return ResponseEntity.ok(Map.of(
                "loader_running", progress.get("running"),
                "total_candles_loaded", progress.get("total_candles_loaded"),
                "symbols_ready", progress.get("symbols_completed"),
                "ready_for_backtest", (long) progress.get("total_candles_loaded") > 100000
        ));
    }
}
