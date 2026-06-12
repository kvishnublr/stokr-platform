package com.stokr.bootstrap.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/signals")
@RequiredArgsConstructor
@Slf4j
public class SignalLifecycleController {

    /**
     * Get execution lifecycle of a specific signal
     */
    @GetMapping("/{signalId}/lifecycle")
    public ResponseEntity<Map<String, Object>> getSignalLifecycle(@PathVariable String signalId) {
        try {
            // In production, query database for actual signal lifecycle events
            // For now, return mock data demonstrating the structure

            Map<String, Object> lifecycle = new LinkedHashMap<>();
            lifecycle.put("signalId", signalId);
            lifecycle.put("symbol", "INDY");
            lifecycle.put("side", "BUY");
            lifecycle.put("quantity", 100);
            lifecycle.put("aiScore", 92);
            lifecycle.put("status", "FILLED");
            lifecycle.put("orderId", "ord-2026-06-10-001");

            // Build execution timeline
            long baseTime = System.currentTimeMillis();
            List<Map<String, Object>> events = new ArrayList<>();

            events.add(buildEvent(baseTime, "SIGNAL_GENERATED", "strategy-service", 0));
            events.add(buildEvent(baseTime + 111, "SIGNAL_QUEUED", "rabbitmq", 111));
            events.add(buildEvent(baseTime + 222, "SIGNAL_RECEIVED", "execution-service", 111));
            events.add(buildEvent(baseTime + 281, "RISK_VALIDATION_START", "execution-service", 59));
            events.add(buildEvent(baseTime + 312, "RISK_VALIDATION_PASSED", "risk-service", 31));
            events.add(buildEvent(baseTime + 335, "BROKER_SUBMISSION", "execution-service", 23));
            events.add(buildEvent(baseTime + 424, "ORDER_FILLED", "broker", 89));
            events.add(buildEvent(baseTime + 512, "POSITION_CREATED", "core-trading", 88));

            lifecycle.put("events", events);
            lifecycle.put("totalLatencyMs", 512);

            return ResponseEntity.ok(lifecycle);
        } catch (Exception e) {
            log.error("Error getting signal lifecycle for: {}", signalId, e);
            return ResponseEntity.status(404).body(Map.of(
                "error", "Signal not found",
                "signalId", signalId
            ));
        }
    }

    /**
     * Search signals by criteria
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> searchSignals(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();

            // In production, query database with filters
            List<Map<String, Object>> signals = new ArrayList<>();
            signals.add(buildSignalSummary("sig-2026-06-10-001", "INDY", "BUY", "FILLED", 92));
            signals.add(buildSignalSummary("sig-2026-06-10-002", "SBIN", "SELL", "FILLED", 85));
            signals.add(buildSignalSummary("sig-2026-06-10-003", "TCS", "BUY", "REJECTED", 78));

            response.put("signals", signals);
            response.put("totalCount", signals.size());
            response.put("page", page);
            response.put("limit", limit);
            response.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error searching signals", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to search signals"));
        }
    }

    /**
     * Get signal statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSignalStats() {
        try {
            Map<String, Object> stats = new LinkedHashMap<>();

            stats.put("totalSignals", 1247);
            stats.put("filledSignals", 1180);
            stats.put("rejectedSignals", 45);
            stats.put("pendingSignals", 22);
            stats.put("fillRate", 94.6);
            stats.put("avgLatencyMs", 234);
            stats.put("p95LatencyMs", 450);
            stats.put("p99LatencyMs", 1200);
            stats.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting signal stats", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to get signal stats"));
        }
    }

    // Helper methods
    private Map<String, Object> buildEvent(long timestamp, String event, String service, long deltaMs) {
        Map<String, Object> eventMap = new LinkedHashMap<>();
        eventMap.put("timestamp", timestamp);
        eventMap.put("event", event);
        eventMap.put("service", service);
        eventMap.put("deltaMs", deltaMs);
        return eventMap;
    }

    private Map<String, Object> buildSignalSummary(String signalId, String symbol, String side,
                                                    String status, int aiScore) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("signalId", signalId);
        signal.put("symbol", symbol);
        signal.put("side", side);
        signal.put("status", status);
        signal.put("aiScore", aiScore);
        signal.put("createdAt", Instant.now().minusSeconds(300).toString());
        return signal;
    }
}
