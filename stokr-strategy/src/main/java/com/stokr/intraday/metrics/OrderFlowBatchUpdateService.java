package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.IntelligenceScore;
import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import com.stokr.intraday.metrics.dto.OrderFlowSignalEnhancement;
import com.stokr.intraday.metrics.repository.IntelligenceScoreRepository;
import com.stokr.intraday.metrics.repository.OrderFlowSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "stokr.orderflow.batch-update-enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class OrderFlowBatchUpdateService {

    private final OrderFlowSnapshotRepository snapshotRepository;
    private final IntelligenceScoreRepository scoreRepository;
    private final OrderFlowMetricsService metricsService;

    @Scheduled(fixedRateString = "${stokr.orderflow.batch-update-interval:60000}")
    @Transactional
    public void updateAllIntelligenceScores() {
        try {
            long startTime = System.currentTimeMillis();
            log.info("???? Starting batch intelligence update for all symbols...");

            // Fetch all unique symbols with valid order flow data
            Set<String> symbols = fetchActiveSymbols();

            if (symbols.isEmpty()) {
                log.debug("??????  No active symbols with order flow data");
                return;
            }

            log.info("???? Processing {} symbols for intelligence update", symbols.size());
            int updated = 0;
            int failed = 0;

            for (String symbol : symbols) {
                try {
                    updateSymbolIntelligence(symbol);
                    updated++;
                } catch (Exception e) {
                    log.warn("??? Failed to update intelligence for symbol: {}", symbol, e);
                    failed++;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("??? Batch intelligence update complete. Updated: {}, Failed: {}, Duration: {}ms",
                    updated, failed, duration);

        } catch (Exception e) {
            log.error("???? Batch intelligence update failed", e);
        }
    }

    private Set<String> fetchActiveSymbols() {
        // Fetch all unique symbols with valid snapshots from last 5 minutes
        List<String> symbols = snapshotRepository.findDistinctSymbolsWithValidSnapshots(
            Instant.now().minusSeconds(300)
        );
        return new HashSet<>(symbols);
    }

    private void updateSymbolIntelligence(String symbol) {
        // Get the signal enhancement for this symbol
        OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal(symbol);

        // Create or update intelligence score record
        IntelligenceScore score = scoreRepository.findBySymbol(symbol)
            .orElseGet(() -> IntelligenceScore.builder()
                .symbol(symbol)
                .build()
            );

        // Update score with latest metrics
        score.setConfidence(signal.getConfidence());
        score.setRecommendation(signal.getRecommendation());
        score.setBuyerPressureScore(signal.getBuyerPressureScore());
        score.setSellerPressureScore(signal.getSellerPressureScore());
        score.setLiquidityScore(signal.getLiquidityScore());

        // Calculate spread score (inverse of spread %)
        if (signal.getSpreadPct() != null) {
            double spreadPct = signal.getSpreadPct().doubleValue();
            int spreadScore = (int) Math.max(0, 100 - (spreadPct * 1000));  // Convert % to 0-100
            score.setSpreadScore(spreadScore);
        }

        score.setConfidenceMultiplier(signal.getConfidenceMultiplier());
        score.setSignalStrength(signal.getSignalStrength());
        score.setShouldEnhanceConfidence(signal.getShouldEnhanceConfidence());
        score.setShouldReduceConfidence(signal.getShouldReduceConfidence());
        score.setShouldSkip(signal.getShouldSkip());
        score.setLastUpdated(Instant.now());

        scoreRepository.save(score);

        log.debug("???? Updated intelligence for {}: confidence={}, recommendation={}",
                symbol, signal.getConfidence(), signal.getRecommendation());
    }

    public void triggerImmediateUpdate() {
        log.info("???? Triggering immediate batch intelligence update...");
        updateAllIntelligenceScores();
    }

    public int getActiveSymbolCount() {
        return (int) snapshotRepository.countDistinctSymbolsWithValidSnapshots(
            Instant.now().minusSeconds(300)
        );
    }
}
