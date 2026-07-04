package com.stokr.marketdata;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints for historical data backfill operations.
 * Protected by /api/admin/** in SecurityConfig (ADMIN role required).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class BackfillController {

    private final HistoricalDataBackfillService backfillService;

    @PostMapping("/backfill/historical")
    public ResponseEntity<Map<String, Object>> triggerBackfill(
            @RequestParam(defaultValue = "6") int months) {
        if (months < 1 || months > 24) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "months must be between 1 and 24"));
        }
        try {
            backfillService.backfill(months);
            return ResponseEntity.ok(Map.of(
                "status", "started",
                "months", months,
                "message", "Backfill running. Check /api/admin/backfill/status for progress."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/backfill/status")
    public ResponseEntity<Map<String, Object>> backfillStatus() {
        return ResponseEntity.ok(backfillService.getProgress());
    }
}
