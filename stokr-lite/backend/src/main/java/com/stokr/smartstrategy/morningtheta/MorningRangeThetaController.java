package com.stokr.smartstrategy.morningtheta;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/morning-theta")
public class MorningRangeThetaController {

    private final MorningRangeThetaScanner scanner;
    private final MorningRangeThetaExecutor executor;
    private final MorningRangeTracker rangeTracker;
    private final com.stokr.smartstrategy.SmartStrategyExecutionService executionService;
    private final com.stokr.smartstrategy.StrategyScoreEngine scoreEngine;

    public MorningRangeThetaController(MorningRangeThetaScanner scanner,
                                        MorningRangeThetaExecutor executor,
                                        MorningRangeTracker rangeTracker,
                                        com.stokr.smartstrategy.SmartStrategyExecutionService executionService,
                                        com.stokr.smartstrategy.StrategyScoreEngine scoreEngine) {
        this.scanner = scanner;
        this.executor = executor;
        this.rangeTracker = rangeTracker;
        this.executionService = executionService;
        this.scoreEngine = scoreEngine;
    }

    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<Map<String, Object>> opps = scanner.scan(underlying);
        List<Map<String, Object>> scored = scoreEngine.rankAndFilter(opps, 0);
        resp.put("opportunities", scored);
        resp.put("count", scored.size());
        resp.put("trackingPhase", rangeTracker.isTrackingPhase());

        Map<String, Object> ranges = new LinkedHashMap<>();
        for (var entry : rangeTracker.getAllRanges().entrySet()) {
            var r = entry.getValue();
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("open", r.openPrice());
            rd.put("high", r.high());
            rd.put("low", r.low());
            rd.put("current", r.currentSpot());
            rd.put("rangePct", r.rangePercent());
            rd.put("dayType", r.dayType().name());
            rd.put("trend", r.trend().name());
            rd.put("atmIV", r.atmIV());
            rd.put("frozen", r.frozen());
            ranges.put(entry.getKey(), rd);
        }
        resp.put("morningRanges", ranges);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(executor.getStatus());
    }

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        boolean enable = Boolean.TRUE.equals(body.get("enabled"));
        executor.setEnabled(enable);
        return ResponseEntity.ok(executor.getStatus());
    }

    @PostMapping("/enter")
    public ResponseEntity<Map<String, Object>> enter(@RequestBody Map<String, Object> request) {
        request.put("strategyType", "MORNING_RANGE_THETA");
        return ResponseEntity.ok(executionService.enterTrade(request));
    }

    @PostMapping("/exit/{positionId}")
    public ResponseEntity<Map<String, Object>> exit(@PathVariable Long positionId) {
        return ResponseEntity.ok(executionService.exitPosition(positionId));
    }

    @GetMapping("/positions")
    public ResponseEntity<List<Map<String, Object>>> positions() {
        return ResponseEntity.ok(executionService.getActivePositions().stream()
            .filter(p -> "MORNING_RANGE_THETA".equals(p.get("strategyType")))
            .toList());
    }
}
