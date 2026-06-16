package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.ConfidenceScore;
import com.stokr.intraday.metrics.domain.ConfidenceStrategyConfig;
import com.stokr.intraday.metrics.repository.ConfidenceScoreRepository;
import com.stokr.intraday.metrics.repository.ConfidenceStrategyConfigRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final StrategySignalPipelineService signalPipelineService;

    // Run every 2 minutes (after confidence calculation)
    @Scheduled(fixedRateString = "${stokr.confidence-strategy.generator-interval:120000}",
              initialDelayString = "${stokr.confidence-strategy.generator-initial-delay:70000}")
    @Transactional
    public void generateSignalsBasedOnConfidence() {
        try {
            long startTime = System.currentTimeMillis();
            log.info("???? Starting signal generation from confidence scores...");

            // Get all enabled trader configurations
            List<ConfidenceStrategyConfig> configs = configRepository.findByEnabledTrue();

            if (configs.isEmpty()) {
                log.info("??????  No traders configured for confidence-based strategy");
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
            log.info("??? Signal generation complete. Total: {}, Duration: {}ms",
                totalSignalsGenerated, duration);

            // Log breakdown by threshold
            signalCountByThreshold.forEach((threshold, count) ->
                log.info("  Threshold {}: {} signals generated", threshold, count)
            );

        } catch (Exception e) {
            log.error("???? Signal generation failed", e);
        }
    }

    private int generateSignalsForConfig(ConfidenceStrategyConfig config) {
        int threshold = config.getMinConfidenceThreshold();
        int signalsGenerated = 0;

        try {
            // Get all scores from last 2 minutes that exceed threshold
            List<ConfidenceScore> highConfidenceScores = scoreRepository
                .findByConfidenceScoreGreaterThanAndTimestampAfterOrderByConfidenceScoreDescTimestampDesc(threshold,
                    Instant.now().minusSeconds(120));

            log.debug("Found {} symbols with confidence > {} for config: {}",
                highConfidenceScores.size(), threshold, config.getStrategyName());

            for (ConfidenceScore score : highConfidenceScores) {
                // Create and persist StrategySignal record
                StrategySignalEntity signal = new StrategySignalEntity();
                signal.setSymbol(score.getSymbol());
                signal.setSignalType(SignalType.BUY);
                signal.setStrategyName(config.getStrategyName());
                signal.setStrategyVersion("1.0");
                signal.setCandleTimestamp(score.getTimestamp());
                signal.setConfidenceScore(BigDecimal.valueOf(score.getConfidenceScore()));
                signal.setUserId(config.getTraderId());
                signal.setReason(String.format("Confidence %.1f > threshold %d. Buyer pressure: %d%%, Liquidity: %d%%",
                    score.getConfidenceScore(), threshold,
                    score.getBuyerPressure() != null ? score.getBuyerPressure() : 0,
                    score.getLiquidityScore() != null ? score.getLiquidityScore() : 0));
                signal.setSimulation(false);
                signal.setOwnerType(SignalOwnerType.AUTO_TRADE);
                signal.setSignalSource(SignalProvenance.PAPER);

                // RUNTIME INSTRUMENTATION: Trace all signals created by this service
                StringBuilder stackTrace = new StringBuilder();
                for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                    if (ste.getClassName().contains("com.stokr")) {
                        stackTrace.append(ste.getClassName()).append(".").append(ste.getMethodName())
                            .append(":").append(ste.getLineNumber()).append("\n");
                    }
                }
                log.warn("CONFIDENCE_SIGNAL_PERSIST_TRACE");
                log.warn("FROM_CONFIDENCE_SERVICE strategy={} symbol={} confidence={} pipeline={} thread={}",
                    config.getStrategyName(), score.getSymbol(), score.getConfidenceScore(),
                    signal.getPipeline(), Thread.currentThread().getName());
                log.warn("CALLER_STACK:\n{}", stackTrace);

                StrategySignalEntity persisted = signalPipelineService.persistAndDispatch(
                        signal,
                        java.util.UUID.randomUUID().toString(),
                        "PAPER",
                        SignalProvenance.PAPER,
                        true
                );
                if (persisted != null) {
                    log.debug("  ??? Signal persisted: {} at {} confidence",
                        score.getSymbol(), score.getConfidenceScore());
                    signalsGenerated++;
                }
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
        return scoreRepository.findByConfidenceScoreGreaterThanAndTimestampAfterOrderByConfidenceScoreDescTimestampDesc(
            threshold,
            Instant.now().minusSeconds(300)
        ).stream().limit(limit).toList();
    }
}
