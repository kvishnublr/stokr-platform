package com.stokr.intraday.metrics.controller;

import com.stokr.intraday.metrics.ConfidenceBasedSignalGeneratorService;
import com.stokr.intraday.metrics.ConfidenceScoreCalculatorService;
import com.stokr.intraday.metrics.domain.ConfidenceScore;
import com.stokr.intraday.metrics.domain.ConfidenceStrategyConfig;
import com.stokr.intraday.metrics.dto.ConfidenceThresholdRequest;
import com.stokr.intraday.metrics.dto.SignalCountByThreshold;
import com.stokr.intraday.metrics.repository.ConfidenceScoreRepository;
import com.stokr.intraday.metrics.repository.ConfidenceStrategyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/confidence-strategy")
@RequiredArgsConstructor
public class ConfidenceStrategyController {

    private final ConfidenceStrategyConfigRepository configRepository;
    private final ConfidenceScoreRepository scoreRepository;
    private final ConfidenceScoreCalculatorService calculatorService;
    private final ConfidenceBasedSignalGeneratorService generatorService;

    @PostMapping("/config")
    public ResponseEntity<ConfidenceStrategyConfig> setConfidenceThreshold(
            @RequestBody ConfidenceThresholdRequest request) {

        try {
            // Check if strategy already exists for this trader
            var existing = configRepository.findByTraderIdAndMinConfidenceThreshold(
                request.getTraderId(),
                request.getThreshold()
            );

            if (existing.isPresent()) {
                return ResponseEntity.ok(existing.get());
            }

            // Create new configuration
            ConfidenceStrategyConfig config = ConfidenceStrategyConfig.builder()
                .traderId(request.getTraderId())
                .minConfidenceThreshold(request.getThreshold())
                .build();

            // This will also set strategyName via setter
            config.setMinConfidenceThreshold(request.getThreshold());
            config.setEnabled(true);

            ConfidenceStrategyConfig saved = configRepository.save(config);

            log.info("✅ Trader {} set confidence threshold to {}",
                request.getTraderId(), request.getThreshold());

            return ResponseEntity.ok(saved);

        } catch (IllegalArgumentException e) {
            log.error("Invalid threshold: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/config/{traderId}")
    public ResponseEntity<List<ConfidenceStrategyConfig>> getTraderConfigs(
            @PathVariable UUID traderId) {

        List<ConfidenceStrategyConfig> configs = configRepository.findByTraderId(traderId);
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/today/signal-count")
    public ResponseEntity<SignalCountByThreshold> getTodaySignalCount() {
        Map<Integer, Long> counts = generatorService.getSignalCountByThreshold(
            Instant.now().minusSeconds(86400)  // Last 24 hours
        );

        return ResponseEntity.ok(SignalCountByThreshold.builder()
            .threshold60(counts.getOrDefault(60, 0L))
            .threshold70(counts.getOrDefault(70, 0L))
            .threshold80(counts.getOrDefault(80, 0L))
            .threshold90(counts.getOrDefault(90, 0L))
            .timestamp(Instant.now())
            .build());
    }

    @GetMapping("/signals/above/{threshold}")
    public ResponseEntity<List<ConfidenceScore>> getSignalsAboveThreshold(
            @PathVariable Integer threshold,
            @RequestParam(defaultValue = "20") int limit) {

        if (threshold != 60 && threshold != 70 && threshold != 80 && threshold != 90) {
            return ResponseEntity.badRequest().build();
        }

        List<ConfidenceScore> signals = generatorService.getSignalsAboveThreshold(
            threshold, limit);

        return ResponseEntity.ok(signals);
    }

    @GetMapping("/latest-scores")
    public ResponseEntity<List<ConfidenceScore>> getLatestScores(
            @RequestParam(defaultValue = "10") int limit) {

        List<ConfidenceScore> scores = scoreRepository.findTopByConfidenceRecent(
            Instant.now().minusSeconds(300),
            limit
        );

        return ResponseEntity.ok(scores);
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Map<String, Object> stats = Map.of(
            "activeSymbols", calculatorService.getActiveSymbolCount(),
            "lastUpdate", Instant.now(),
            "configuredTraders", configRepository.findByEnabledTrue().size()
        );

        return ResponseEntity.ok(stats);
    }

    @PostMapping("/test/calculate-now")
    public ResponseEntity<String> triggerCalculationNow() {
        log.info("🔄 Manual trigger: Starting confidence calculation");
        calculatorService.calculateConfidenceForAllNifty100();
        return ResponseEntity.ok("Calculation triggered");
    }

    @PostMapping("/test/generate-signals-now")
    public ResponseEntity<String> triggerSignalGenerationNow() {
        log.info("🎯 Manual trigger: Starting signal generation");
        generatorService.generateSignalsBasedOnConfidence();
        return ResponseEntity.ok("Signal generation triggered");
    }
}
