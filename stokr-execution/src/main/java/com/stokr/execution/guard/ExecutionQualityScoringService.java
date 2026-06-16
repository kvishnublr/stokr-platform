package com.stokr.execution.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExecutionQualityScoringService {

    private final ExecutionQualityMetricRepository qualityMetricRepository;
    private final ExecutionGuardEventRepository guardEventRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> scoreForUser(UUID userId) {
        List<ExecutionQualityMetric> quality = qualityMetricRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, PageRequest.of(0, 500));
        List<ExecutionGuardEvent> events = guardEventRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, PageRequest.of(0, 500));

        BigDecimal avgSlip = avg(quality.stream().map(ExecutionQualityMetric::getRealizedSlippagePct).toList());
        BigDecimal avgLatency = avgLong(quality.stream().map(ExecutionQualityMetric::getFillLatencyMs).toList());
        long hardFails = events.stream().filter(e -> e.getOutcome() == ExecutionGuardOutcome.HARD_FAIL).count();
        long softFails = events.stream().filter(e -> e.getOutcome() == ExecutionGuardOutcome.SOFT_FAIL).count();
        long totalGuard = Math.max(1L, events.size());
        BigDecimal rejectionRate = BigDecimal.valueOf(hardFails * 100.0 / totalGuard).setScale(4, RoundingMode.HALF_UP);

        int score = 100;
        if (avgSlip != null) score -= Math.min(30, avgSlip.multiply(BigDecimal.valueOf(100)).intValue());
        if (avgLatency != null) score -= Math.min(20, avgLatency.divide(BigDecimal.valueOf(25), RoundingMode.HALF_UP).intValue());
        score -= Math.min(35, (int) (hardFails * 2));
        score -= Math.min(15, (int) (softFails));
        score = Math.max(0, Math.min(100, score));

        String bucket = score >= 90 ? "EXCELLENT" : score >= 70 ? "NORMAL" : score >= 50 ? "WARNING" : "UNHEALTHY";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("bucket", bucket);
        out.put("avgSlippagePct", avgSlip);
        out.put("avgLatencyMs", avgLatency);
        out.put("hardFailCount", hardFails);
        out.put("softFailCount", softFails);
        out.put("rejectionRatePct", rejectionRate);
        out.put("strategyRanking", strategyRanking(quality));
        return out;
    }

    private static List<Map<String, Object>> strategyRanking(List<ExecutionQualityMetric> rows) {
        return rows.stream()
                .collect(Collectors.groupingBy(x -> x.getStrategyKey() == null ? "UNKNOWN" : x.getStrategyKey()))
                .entrySet().stream()
                .map(e -> {
                    BigDecimal avgSlip = avg(e.getValue().stream().map(ExecutionQualityMetric::getRealizedSlippagePct).toList());
                    BigDecimal avgLatency = avgLong(e.getValue().stream().map(ExecutionQualityMetric::getFillLatencyMs).toList());
                    int s = 100;
                    if (avgSlip != null) s -= Math.min(40, avgSlip.multiply(BigDecimal.valueOf(100)).intValue());
                    if (avgLatency != null) s -= Math.min(30, avgLatency.divide(BigDecimal.valueOf(20), RoundingMode.HALF_UP).intValue());
                    s = Math.max(0, Math.min(100, s));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("strategyKey", e.getKey());
                    m.put("score", s);
                    m.put("samples", e.getValue().size());
                    m.put("avgSlippagePct", avgSlip);
                    m.put("avgLatencyMs", avgLatency);
                    return m;
                })
                .sorted((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")))
                .toList();
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> v = values.stream().filter(x -> x != null).toList();
        if (v.isEmpty()) return null;
        BigDecimal sum = v.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(v.size()), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal avgLong(List<Long> values) {
        List<Long> v = values.stream().filter(x -> x != null).toList();
        if (v.isEmpty()) return null;
        long sum = v.stream().mapToLong(Long::longValue).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(v.size()), 2, RoundingMode.HALF_UP);
    }
}

