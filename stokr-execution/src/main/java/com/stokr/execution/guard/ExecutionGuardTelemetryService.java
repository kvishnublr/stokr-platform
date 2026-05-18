package com.stokr.execution.guard;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExecutionGuardTelemetryService {

    private final ExecutionGuardEventRepository guardEventRepository;
    private final ExecutionQualityMetricRepository qualityMetricRepository;
    private final ExecutionTimelineService timelineService;
    private final ExecutionGuardRealtimePublisher realtimePublisher;
    private final MeterRegistry meterRegistry;

    public void recordGuardEvent(UUID userId, UUID orderId, ExecutionContext ctx, ExecutionGuardResult result) {
        ExecutionGuardEvent row = new ExecutionGuardEvent();
        row.setUserId(userId);
        row.setOrderId(orderId);
        row.setSignalId(ctx.signalMeta() != null ? ctx.signalMeta().signalId() : null);
        row.setStrategyKey(ctx.strategyKey());
        row.setSymbol(ctx.symbol());
        row.setSide(ctx.side());
        row.setGuardMode(ctx.guardMode());
        row.setOutcome(result.outcome());
        ExecutionGuardViolation primary = result.violations().isEmpty() ? null : result.violations().getFirst();
        row.setPrimaryCode(primary != null ? primary.code() : "PASS");
        row.setMessage(primary != null ? primary.message() : "Execution guard passed");
        row.setSignalGeneratedAt(ctx.signalMeta() != null ? ctx.signalMeta().signalGeneratedAt() : null);
        row.setValidationTime(ctx.now());
        if (ctx.signalMeta() != null && ctx.signalMeta().signalGeneratedAt() != null) {
            row.setSignalAgeMs(Math.max(0L, Duration.between(ctx.signalMeta().signalGeneratedAt(), ctx.now()).toMillis()));
        }
        row.setTtlMs(result.policy() != null ? result.policy().maxSignalAgeMs() : null);
        row.setSignalPrice(ctx.signalMeta() != null ? ctx.signalMeta().signalReferencePrice() : null);
        row.setCurrentLtp(result.market() != null ? result.market().ltp() : null);
        row.setDriftPct(computeDriftPct(ctx.signalMeta() != null ? ctx.signalMeta().signalReferencePrice() : null, result.market() != null ? result.market().ltp() : null));
        row.setAllowedDriftPct(result.policy() != null ? result.policy().maxDriftPct() : null);
        row.setLastTickAt(result.market() != null ? result.market().lastTickAt() : null);
        row.setLastHeartbeatAt(result.market() != null ? result.market().lastHeartbeatAt() : null);
        if (result.market() != null && result.market().lastTickAt() != null) {
            row.setStaleDurationMs(Math.max(0L, Duration.between(result.market().lastTickAt(), ctx.now()).toMillis()));
        }
        row.setValidationLatencyMs(result.validationLatencyMs());
        guardEventRepository.save(row);

        String severity = result.outcome() == ExecutionGuardOutcome.HARD_FAIL ? "CRITICAL"
                : result.outcome() == ExecutionGuardOutcome.SOFT_FAIL ? "WARNING" : "INFO";
        timelineService.append(
                orderId != null ? orderId : UUID.randomUUID(),
                null,
                userId,
                ctx.signalMeta() != null ? ctx.signalMeta().signalId() : null,
                ctx.strategyKey(),
                ctx.symbol(),
                "GUARD_VALIDATED",
                ctx.now(),
                Map.of(
                        "violations", result.violations().stream().map(v -> Map.of(
                                "code", v.code(),
                                "severity", v.severity().name(),
                                "message", v.message(),
                                "detail", v.detail()
                        )).toList()
                ),
                result.validationLatencyMs(),
                result.market() == null ? Map.of() : Map.of(
                        "ltp", result.market().ltp(),
                        "bestBid", result.market().bestBid(),
                        "bestAsk", result.market().bestAsk(),
                        "lastTickAt", result.market().lastTickAt() == null ? null : result.market().lastTickAt().toString(),
                        "wsState", result.market().websocketState()
                ),
                result.outcome().name(),
                severity
        );
        Map<String, Object> realtimePayload = new LinkedHashMap<>();
        realtimePayload.put("orderId", orderId != null ? orderId.toString() : null);
        realtimePayload.put("reason", row.getMessage());
        realtimePayload.put("code", row.getPrimaryCode());
        realtimePayload.put("latencyMs", row.getValidationLatencyMs());
        realtimePublisher.publish(
                userId,
                severity,
                result.outcome().name(),
                ctx.symbol(),
                ctx.strategyKey(),
                "execution_guard",
                realtimePayload
        );
        Counter.builder("stokr.execution.guard.events")
                .tag("outcome", result.outcome().name())
                .tag("severity", severity)
                .register(meterRegistry)
                .increment();
    }

    public void recordFillQuality(OmsOrder order, OmsExecution ex) {
        if (order == null || ex == null) return;
        if (qualityMetricRepository.findFirstByOrderIdAndDeletedFalse(order.getId()).isPresent()) return;
        ExecutionQualityMetric q = new ExecutionQualityMetric();
        q.setUserId(order.getUserId());
        q.setOrderId(order.getId());
        q.setSignalId(order.getSignalId());
        q.setSymbol(order.getSymbol());
        q.setStrategyKey(order.getStrategyKey());
        q.setExpectedPrice(order.getEntryReferencePrice() != null ? order.getEntryReferencePrice() : order.getLimitPrice());
        q.setActualFillPrice(ex.getAvgPrice());
        q.setRealizedSlippagePct(computeDriftPct(q.getExpectedPrice(), q.getActualFillPrice()));
        q.setFillLatencyMs(ex.getLatencyMs());
        q.setSpreadAtExecutionPct(ex.getSpreadBps() == null
                ? null
                : ex.getSpreadBps().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        q.setVolatilityAtExecutionPct(ex.getSlippageBps() == null
                ? null
                : ex.getSlippageBps().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        q.setCapturedAt(Instant.now());
        qualityMetricRepository.save(q);
        timelineService.append(
                order.getId(),
                null,
                order.getUserId(),
                order.getSignalId(),
                order.getStrategyKey(),
                order.getSymbol(),
                "FILL_RECORDED",
                Instant.now(),
                Map.of(
                        "expectedPrice", q.getExpectedPrice(),
                        "actualFillPrice", q.getActualFillPrice(),
                        "realizedSlippagePct", q.getRealizedSlippagePct()
                ),
                q.getFillLatencyMs(),
                Map.of(
                        "spreadAtExecutionPct", q.getSpreadAtExecutionPct(),
                        "volatilityAtExecutionPct", q.getVolatilityAtExecutionPct()
                ),
                "PASS",
                "INFO"
        );
        realtimePublisher.publish(
                order.getUserId(),
                "INFO",
                "PASS",
                order.getSymbol(),
                order.getStrategyKey(),
                "execution_fill_quality",
                Map.of(
                        "orderId", order.getId().toString(),
                        "slippagePct", q.getRealizedSlippagePct(),
                        "latencyMs", q.getFillLatencyMs()
                )
        );
        Counter.builder("stokr.execution.fill.quality")
                .tag("strategy", order.getStrategyKey() == null ? "UNKNOWN" : order.getStrategyKey())
                .register(meterRegistry)
                .increment();
    }

    public List<Map<String, Object>> recentGuardEvents(UUID userId, int limit) {
        return guardEventRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(200, limit))))
                .stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId().toString());
                    m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
                    m.put("symbol", e.getSymbol());
                    m.put("side", e.getSide());
                    m.put("strategyKey", e.getStrategyKey());
                    m.put("guardMode", e.getGuardMode() != null ? e.getGuardMode().name() : null);
                    m.put("outcome", e.getOutcome() != null ? e.getOutcome().name() : null);
                    m.put("severity", e.getOutcome() == ExecutionGuardOutcome.HARD_FAIL ? "CRITICAL"
                            : e.getOutcome() == ExecutionGuardOutcome.SOFT_FAIL ? "WARNING" : "INFO");
                    m.put("code", e.getPrimaryCode());
                    m.put("message", e.getMessage());
                    m.put("reason", e.getMessage());
                    m.put("driftPct", e.getDriftPct());
                    m.put("staleMs", e.getStaleDurationMs());
                    m.put("validationLatencyMs", e.getValidationLatencyMs());
                    return m;
                }).toList();
    }

    public List<Map<String, Object>> recentQualityMetrics(UUID userId, int limit) {
        return qualityMetricRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(200, limit))))
                .stream()
                .map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", q.getId().toString());
                    m.put("createdAt", q.getCreatedAt() != null ? q.getCreatedAt().toString() : null);
                    m.put("symbol", q.getSymbol());
                    m.put("strategyKey", q.getStrategyKey());
                    m.put("expectedPrice", q.getExpectedPrice());
                    m.put("actualFillPrice", q.getActualFillPrice());
                    m.put("realizedSlippagePct", q.getRealizedSlippagePct());
                    m.put("fillLatencyMs", q.getFillLatencyMs());
                    m.put("spreadAtExecutionPct", q.getSpreadAtExecutionPct());
                    m.put("spreadAtExecution", q.getSpreadAtExecutionPct());
                    m.put("volatilityAtExecutionPct", q.getVolatilityAtExecutionPct());
                    m.put("volatilityAtExecution", q.getVolatilityAtExecutionPct());
                    m.put("executionMode", null);
                    return m;
                }).toList();
    }

    private static BigDecimal computeDriftPct(BigDecimal ref, BigDecimal cur) {
        if (ref == null || cur == null || ref.compareTo(BigDecimal.ZERO) == 0) return null;
        return cur.subtract(ref).abs().multiply(BigDecimal.valueOf(100)).divide(ref, 6, RoundingMode.HALF_UP);
    }
}
