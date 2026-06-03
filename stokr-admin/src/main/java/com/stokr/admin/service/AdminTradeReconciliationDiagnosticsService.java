package com.stokr.admin.service;

import com.stokr.execution.comparison.ExecutionComparisonMetrics;
import com.stokr.execution.comparison.ExecutionComparisonMetricsRepository;
import com.stokr.execution.comparison.ReconciliationSafetyMonitorService;
import com.stokr.execution.validation.ValidationPromotionGuardrailsService;
import com.stokr.strategy.validation.StrategyValidationMetrics;
import com.stokr.strategy.validation.StrategyValidationMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminTradeReconciliationDiagnosticsService {

    private final ExecutionComparisonMetricsRepository comparisonRepository;
    private final StrategyValidationMetricsRepository validationMetricsRepository;
    private final ReconciliationSafetyMonitorService safetyMonitorService;
    private final ValidationPromotionGuardrailsService promotionGuardrailsService;
    private final AdminStrategyValidationDiagnosticsService strategyValidationDiagnosticsService;

    public Map<String, Object> fullDiagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradePairs", Map.of("pairs", recentTradePairs(50)));
        out.put("unreconciled", unreconciledTrades(30));
        out.put("reconciliationFailures", failedTrades(20));
        out.put("lifecycleDivergence", lifecycleDivergenceSample(20));
        out.put("driftAnalytics", driftAnalytics());
        out.put("safetyScan", safetyMonitorService.runSafetyScan());
        out.put("promotionGuardrails", promotionGuardrailsService.evaluateAll());
        out.put("strategyValidation", strategyValidationDiagnosticsService.diagnostics());
        return out;
    }

    public Map<String, Object> tradePairs(int limit) {
        return Map.of("pairs", recentTradePairs(limit));
    }

    private List<Map<String, Object>> recentTradePairs(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        comparisonRepository.findAll(PageRequest.of(0, limit)).getContent().stream()
                .filter(m -> !m.isDeleted())
                .forEach(m -> rows.add(pairSnapshot(m)));
        return rows;
    }

    private List<Map<String, Object>> unreconciledTrades(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        comparisonRepository.findStaleUnreconciled(
                java.time.Instant.now().plusSeconds(3600),
                PageRequest.of(0, limit)).forEach(m -> rows.add(pairSnapshot(m)));
        return rows;
    }

    private List<Map<String, Object>> failedTrades(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        comparisonRepository.findAll(PageRequest.of(0, 200)).getContent().stream()
                .filter(m -> !m.isDeleted())
                .filter(m -> "FAILED".equals(m.getReconciliationStatus()))
                .limit(limit)
                .forEach(m -> rows.add(pairSnapshot(m)));
        return rows;
    }

    private List<Map<String, Object>> lifecycleDivergenceSample(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        comparisonRepository.findAll(PageRequest.of(0, 200)).getContent().stream()
                .filter(m -> !m.isDeleted())
                .filter(m -> "RECONCILED".equals(m.getReconciliationStatus()))
                .filter(m -> m.getPnlDrift() != null || m.getHoldTimeDrift() != null)
                .limit(limit)
                .forEach(m -> {
                    Map<String, Object> row = pairSnapshot(m);
                    row.put("pnlDrift", m.getPnlDrift());
                    row.put("holdTimeDriftSec", m.getHoldTimeDrift());
                    row.put("exitCategoryDrift", !java.util.Objects.equals(
                            m.getPaperExitCategory(), m.getLiveExitCategory()));
                    rows.add(row);
                });
        return rows;
    }

    private Map<String, Object> driftAnalytics() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> byStrategy = new ArrayList<>();
        long pairCountToday = comparisonRepository.findAll().stream()
                .filter(m -> !m.isDeleted())
                .filter(m -> m.getCreatedAt() != null
                        && !m.getCreatedAt().isBefore(today.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant()))
                .count();
        boolean pairMetricsAvailable = false;
        for (StrategyValidationMetrics m : validationMetricsRepository.findAll()) {
            if (!today.equals(m.getSessionDate())) {
                continue;
            }
            Map<String, Object> row = driftRow(m);
            if (hasPairDriftMetrics(row)) {
                pairMetricsAvailable = true;
            }
            byStrategy.add(row);
        }
        out.put("today", byStrategy);
        out.put("meta", Map.of(
                "sessionDate", today.toString(),
                "reconciledPairCountToday", pairCountToday,
                "pairMetricsAvailable", pairMetricsAvailable,
                "pairMetricsRequireBothMode",
                "Paper/live slippage, latency, and win-rate delta require BOTH-mode LIVE+PAPER "
                        + "orders reconciled in execution_comparison_metrics."));
        return out;
    }

    private static Map<String, Object> driftRow(StrategyValidationMetrics m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("strategyKey", m.getStrategyName());
        row.put("expectancyDrift", m.getExpectancyDrift());
        row.put("slippageP50Bps", m.getSlippageP50Bps());
        row.put("slippageP95Bps", m.getSlippageP95Bps());
        row.put("latencyP50Ms", m.getLatencyP50Ms());
        row.put("latencyP95Ms", m.getLatencyP95Ms());
        row.put("exitTimingDriftSec", m.getExitTimingDriftSeconds());
        row.put("liveUnderperformancePct", m.getLiveUnderperformancePct());
        row.put("strategyDegradationScore",
                m.getStrategyDegradationScore() != null
                        ? m.getStrategyDegradationScore()
                        : computeDegradationScoreFallback(m));
        row.put("winRateDelta", m.getWinRateDelta());
        row.put("signalsGenerated", m.getSignalsGenerated());
        row.put("sampleSize", m.getSampleSize());
        row.put("omsRejectRate", m.getOmsRejectRate());
        row.put("integrityRejectionPct", m.getIntegrityRejectionPct());
        row.put("paperPnl", m.getPaperPnl());
        row.put("winRate", m.getWinRate());
        return row;
    }

    private static boolean hasPairDriftMetrics(Map<String, Object> row) {
        return row.get("slippageP50Bps") != null
                || row.get("latencyP50Ms") != null
                || row.get("winRateDelta") != null;
    }

    private static BigDecimal computeDegradationScoreFallback(StrategyValidationMetrics row) {
        double score = 0;
        if (row.getPaperLiveDrift() != null) {
            score += Math.min(40, row.getPaperLiveDrift().abs().doubleValue());
        }
        if (row.getAvgSlippageBps() != null) {
            score += Math.min(25, row.getAvgSlippageBps().doubleValue());
        }
        if (row.getOmsRejectRate() != null) {
            score += Math.min(20, row.getOmsRejectRate().doubleValue());
        }
        if (row.getIntegrityRejectionPct() != null) {
            score += Math.min(15, row.getIntegrityRejectionPct().doubleValue());
        }
        if (score == 0) {
            return null;
        }
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> pairSnapshot(ExecutionComparisonMetrics m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signalId", m.getSignalId());
        row.put("strategyKey", m.getStrategyKey());
        row.put("symbol", m.getSymbol());
        row.put("reconciliationStatus", m.getReconciliationStatus());
        row.put("paperOrderId", m.getPaperOrderId());
        row.put("liveOrderId", m.getLiveOrderId());
        row.put("paperEntryPrice", m.getPaperEntryPrice());
        row.put("liveEntryPrice", m.getLiveEntryPrice());
        row.put("paperExitPrice", m.getPaperExitPrice());
        row.put("liveExitPrice", m.getLiveExitPrice());
        row.put("paperRealizedPnl", m.getPaperRealizedPnl());
        row.put("liveRealizedPnl", m.getLiveRealizedPnl());
        row.put("pnlDrift", m.getPnlDrift());
        row.put("paperHoldSeconds", m.getPaperHoldSeconds());
        row.put("liveHoldSeconds", m.getLiveHoldSeconds());
        row.put("holdTimeDrift", m.getHoldTimeDrift());
        row.put("paperExitCategory", m.getPaperExitCategory());
        row.put("liveExitCategory", m.getLiveExitCategory());
        row.put("slippageDivergencePct", m.getSlippageDivergencePct());
        row.put("fillCountDifference", m.getFillCountDifference());
        row.put("partialFillDifference", m.getPartialFillDifference());
        row.put("quantityDrift", m.getQuantityDrift());
        row.put("paperFillCount", m.getPaperFillCount());
        row.put("liveFillCount", m.getLiveFillCount());
        row.put("reconciledAt", m.getReconciledAt());
        row.put("failureReason", m.getReconciliationFailureReason());
        return row;
    }
}
