package com.stokr.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    @GetMapping
    public ResponseEntity<List<Strategy>> getAllStrategies() {
        return ResponseEntity.ok(strategyService.getAllStrategies());
    }

    @GetMapping("/catalog")
    public ResponseEntity<?> getCatalog(@RequestParam(defaultValue = "100") int size) {
        List<Strategy> strategies = strategyService.getAllStrategies();
        return ResponseEntity.ok(java.util.Map.of("data", java.util.Map.of("content", strategies)));
    }

    @GetMapping("/enabled")
    public ResponseEntity<List<Strategy>> getEnabledStrategies() {
        return ResponseEntity.ok(strategyService.getEnabledStrategies());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Strategy> getStrategy(@PathVariable Long id) {
        return ResponseEntity.ok(strategyService.getStrategy(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Strategy> createStrategy(@RequestBody Strategy strategy) {
        return ResponseEntity.ok(strategyService.createStrategy(strategy));
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Strategy> toggleEnabled(@PathVariable Long id,
                                                   @RequestParam boolean enabled) {
        return ResponseEntity.ok(strategyService.toggleEnabled(id, enabled));
    }

    @GetMapping("/runtime-metrics")
    public ResponseEntity<?> getRuntimeMetrics() {
        return ResponseEntity.ok(java.util.Map.of("data", java.util.List.of()));
    }

    @GetMapping("/instances")
    public ResponseEntity<?> getInstances(@RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(java.util.Map.of("data", java.util.Map.of("content", java.util.List.of())));
    }

    @GetMapping("/catalog/signal-stats")
    public ResponseEntity<?> getCatalogSignalStats() {
        return ResponseEntity.ok(java.util.Map.of("data", java.util.List.of()));
    }

    @GetMapping("/runtime-metrics/pipeline-status")
    public ResponseEntity<?> getPipelineStatus() {
        return ResponseEntity.ok(java.util.Map.of("data", java.util.Map.of("executionPipelineActive", true)));
    }
}
