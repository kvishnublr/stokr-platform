package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.ConfidenceScore;
import com.stokr.intraday.metrics.domain.ConfidenceStrategyConfig;
import com.stokr.intraday.metrics.repository.ConfidenceScoreRepository;
import com.stokr.intraday.metrics.repository.ConfidenceStrategyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.generator-enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class ConfidenceBasedSignalGeneratorService {

    private final ConfidenceScoreRepository scoreRepository;
    private final ConfidenceStrategyConfigRepository configRepository;

    // Run every 2 minutes (after confidence calculation)
    @Scheduled(fixedRateString = "${stokr.confidence-strategy.generator-interval:120000}",
              initialDelayString = "${stokr.confidence-strategy.generator-initial-delay:70000}")
    @Transactional
    public void generateSignalsBasedOnConfidence() {
        try {
            long startTime = System.currentTimeMillis();
            log.info("🎯 Starting signal generation from confidence scores...");

            // Get all enabled trader configurations
            List<ConfidenceStrategyConfig> configs = configRepository.findByEnabledTrue();

            if (configs.isEmpty()) {
                log.info("⚠️  No traders configured for confidence-based strategy");
                return;
            }

            Map<Integer, Integer> signalCountByThreshold = new HashMap<>();
            int totalSignalsGenerated = 0;

            for (ConfidenceStrategyConfig config : configs) {
                int signalsForThreshold = generateSignalsForConfig(config);
                signalCountByThreshold.merge(config.getMinConfidenceThreshold(),
                    signalsForThreshold, Integer::sum);
                totalSignalsGenerated += signalsForThreshold;
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Signal generation complete. Total: {}, Duration: {}ms",
                totalSignalsGenerated, duration);

            // Log breakdown by threshold
            signalCountByThreshold.forEach((threshold, count) ->
                log.info("  Threshold {}: {} signals generated", threshold, count)
            );

        } catch (Exception e) {
            log.error("💥 Signal generation failed", e);
        }
    }

    private int generateSignalsForConfig(ConfidenceStrategyConfig config) {
        int threshold = config.getMinConfidenceThreshold();
        int signalsGenerated = 0;

        try {
            // Get all scores from last 2 minutes that exceed threshold
            List<ConfidenceScore> highConfidenceScores = scoreRepository
                .findRecentByConfidenceThreshold(threshold,
                    Instant.now().minusSeconds(120));

            log.debug("Found {} symbols with confidence > {} for config: {}",
                highConfidenceScores.size(), threshold, config.getStrategyName());

            for (ConfidenceScore score : highConfidenceScores) {
                // In a real implementation, here we would:
                // 1. Check if signal already exists
                // 2. Create new StrategySignal record
                // 3. Calculate entry/target/SL based on price + confidence
                // 4. Store in strategy_signals table

                // For now, just log the potential signal
                log.debug("  ✅ Signal candidate: {} at {} confidence",
                    score.getSymbol(), score.getConfidenceScore());

                signalsGenerated++;
            }

        } catch (Exception e) {
            log.warn("Failed to generate signals for config: {}", config, e);
        }

        return signalsGenerated;
    }

    public Map<Integer, Long> getSignalCountByThreshold(Instant since) {
        Map<Integer, Long> counts = new HashMap<>();

        int[] thresholds = {60, 70, 80, 90};
        for (int threshold : thresholds) {
            long count = scoreRepository.countScoresAboveThreshold(threshold, since);
            counts.put(threshold, count);
        }

        return counts;
    }

    public List<ConfidenceScore> getSignalsAboveThreshold(int threshold, int limit) {
        return scoreRepository.findTopByConfidenceRecent(
            Instant.now().minusSeconds(300),
            limit
        );
    }
}
