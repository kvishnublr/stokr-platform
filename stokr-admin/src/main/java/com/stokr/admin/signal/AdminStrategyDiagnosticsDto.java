package com.stokr.admin.signal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AdminStrategyDiagnosticsDto(
        String windowStart,
        String windowEnd,
        long productionSignalCount,
        long confidenceNullCount,
        long confidenceV2Count,
        Map<String, Long> byOwnerType,
        Map<String, Long> byLifecycleStatus,
        Map<String, Long> byOutcomeStatus,
        BigDecimal avgConfidenceScore,
        BigDecimal avgProbability,
        List<SignalConfidenceRow> recentProductionSignals
) {
    public record SignalConfidenceRow(
            String signalId,
            String strategyName,
            String symbol,
            String ownerType,
            String lifecycleStatus,
            BigDecimal confidenceScore,
            BigDecimal probability,
            String tradeQuality,
            String confidenceVersion,
            boolean breakdownPresent,
            BigDecimal entryPrice,
            BigDecimal targetPrice,
            BigDecimal stopPrice,
            Double unifiedAiScore
    ) {
    }
}
