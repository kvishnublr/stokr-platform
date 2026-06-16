package com.stokr.execution.validation;

import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.validation.StrategyValidationMetrics;
import com.stokr.strategy.validation.StrategyValidationMetricsRepository;
import com.stokr.strategy.validation.StrategyValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationPromotionGuardrailsService {

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyValidationMetricsRepository metricsRepository;
    private final FeedHealthMonitorService feedHealthMonitorService;

    @Value("${stokr.validation.promotion.min-sample-size:30}")
    private long minSampleSize;

    @Value("${stokr.validation.promotion.max-pnl-drift-pct:15}")
    private BigDecimal maxPnlDriftPct;

    @Value("${stokr.validation.promotion.max-oms-reject-pct:5}")
    private BigDecimal maxOmsRejectPct;

    @Value("${stokr.validation.promotion.max-slippage-bps:25}")
    private BigDecimal maxSlippageBps;

    @Value("${stokr.validation.promotion.max-integrity-reject-pct:10}")
    private BigDecimal maxIntegrityRejectPct;

    @Value("${stokr.validation.promotion.min-feed-uptime-pct:95}")
    private BigDecimal minFeedUptimePct;

    public Map<String, Object> evaluateAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> strategies = new ArrayList<>();
        for (StrategyDefinition def : definitionRepository.findAllByDeletedFalse(PageRequest.of(0, 500)).getContent()) {
            strategies.add(evaluateStrategy(def.getStrategyKey(), def.getValidationStatus()));
        }
        out.put("strategies", strategies);
        out.put("thresholds", Map.of(
                "minSampleSize", minSampleSize,
                "maxPnlDriftPct", maxPnlDriftPct,
                "maxOmsRejectPct", maxOmsRejectPct,
                "maxSlippageBps", maxSlippageBps,
                "maxIntegrityRejectPct", maxIntegrityRejectPct,
                "minFeedUptimePct", minFeedUptimePct
        ));
        return out;
    }

    public Map<String, Object> evaluateStrategy(String strategyKey, String currentStatus) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        StrategyValidationMetrics metrics = metricsRepository.findByStrategyNameAndSessionDate(strategyKey, today)
                .orElse(null);

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        long sampleSize = rollingSampleSize(strategyKey);
        if (sampleSize < minSampleSize) {
            blockers.add("MIN_SAMPLE_SIZE:" + sampleSize + "<" + minSampleSize);
        }

        if (metrics != null) {
            if (metrics.getPaperLiveDrift() != null && metrics.getPaperLiveDrift().abs().compareTo(maxPnlDriftPct) > 0) {
                blockers.add("PNL_DRIFT:" + metrics.getPaperLiveDrift().toPlainString() + "%");
            }
            if (metrics.getOmsRejectRate() != null && metrics.getOmsRejectRate().compareTo(maxOmsRejectPct) > 0) {
                blockers.add("OMS_REJECT:" + metrics.getOmsRejectRate().toPlainString() + "%");
            }
            if (metrics.getAvgSlippageBps() != null && metrics.getAvgSlippageBps().compareTo(maxSlippageBps) > 0) {
                blockers.add("SLIPPAGE:" + metrics.getAvgSlippageBps().toPlainString() + "bps");
            }
            if (metrics.getIntegrityRejectionPct() != null
                    && metrics.getIntegrityRejectionPct().compareTo(maxIntegrityRejectPct) > 0) {
                blockers.add("INTEGRITY_REJECT:" + metrics.getIntegrityRejectionPct().toPlainString() + "%");
            }
            if (metrics.getUnreconciledTrades() > 0) {
                warnings.add("UNRECONCILED_TRADES:" + metrics.getUnreconciledTrades());
            }
            if (metrics.getReconciliationFailures() > 0) {
                blockers.add("RECONCILIATION_FAILURES:" + metrics.getReconciliationFailures());
            }
        } else {
            warnings.add("NO_METRICS_FOR_TODAY");
        }

        BigDecimal feedUptime = resolveFeedUptime();
        if (feedUptime != null && feedUptime.compareTo(minFeedUptimePct) < 0) {
            blockers.add("FEED_UPTIME:" + feedUptime.toPlainString() + "%");
        }

        StrategyValidationStatus status = StrategyValidationStatus.parse(currentStatus);
        boolean eligibleForLiveCandidate = status == StrategyValidationStatus.PAPER_VALIDATING
                || status == StrategyValidationStatus.LIVE_SHADOW;
        boolean promotionAllowed = eligibleForLiveCandidate && blockers.isEmpty();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategyKey", strategyKey);
        result.put("currentStatus", currentStatus);
        result.put("sampleSize", sampleSize);
        result.put("promotionAllowed", promotionAllowed);
        result.put("targetStatus", promotionAllowed ? "LIVE_CANDIDATE" : currentStatus);
        result.put("blockers", blockers);
        result.put("warnings", warnings);
        result.put("todayMetrics", metricsSnapshot(metrics));
        result.put("feedUptimePct", feedUptime);
        return result;
    }

    public boolean canPromoteToLiveCandidate(String strategyKey) {
        return definitionRepository.findByStrategyKeyAndDeletedFalse(strategyKey)
                .map(def -> {
                    Map<String, Object> eval = evaluateStrategy(def.getStrategyKey(), def.getValidationStatus());
                    return Boolean.TRUE.equals(eval.get("promotionAllowed"));
                })
                .orElse(false);
    }

    private long rollingSampleSize(String strategyKey) {
        return metricsRepository.findByStrategyNameOrderBySessionDateDesc(strategyKey).stream()
                .limit(20)
                .mapToLong(StrategyValidationMetrics::getSampleSize)
                .sum();
    }

    private BigDecimal resolveFeedUptime() {
        Map<String, Object> snap = feedHealthMonitorService.snapshotMap(Instant.now());
        Object uptime = snap.get("uptimePct");
        if (uptime instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (uptime instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> metricsSnapshot(StrategyValidationMetrics m) {
        if (m == null) {
            return Map.of();
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("sessionDate", m.getSessionDate().toString());
        snap.put("sampleSize", m.getSampleSize());
        snap.put("paperPnl", m.getPaperPnl());
        snap.put("livePnl", m.getLivePnl());
        snap.put("paperLiveDrift", m.getPaperLiveDrift());
        snap.put("omsRejectRate", m.getOmsRejectRate());
        snap.put("avgSlippageBps", m.getAvgSlippageBps());
        snap.put("strategyDegradationScore", m.getStrategyDegradationScore());
        snap.put("unreconciledTrades", m.getUnreconciledTrades());
        return snap;
    }
}
