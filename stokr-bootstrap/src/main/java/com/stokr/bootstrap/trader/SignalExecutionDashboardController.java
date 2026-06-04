package com.stokr.bootstrap.trader;

import com.stokr.execution.service.SignalExecutionFallbackService;
import com.stokr.execution.tracking.SignalExecutionTrack;
import com.stokr.execution.tracking.SignalExecutionTrackRepository;
import com.stokr.execution.tracking.SignalExecutionTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trader/signals")
@RequiredArgsConstructor
public class SignalExecutionDashboardController {

    private final SignalExecutionTrackingService trackingService;
    private final SignalExecutionFallbackService fallbackService;
    private final SignalExecutionTrackRepository trackRepository;

    /**
     * Get signal execution status and tracking details.
     * Includes broker info, execution mode, and real-time status.
     */
    @GetMapping("/{signalId}/track")
    public Map<String, Object> getSignalTrack(
            @PathVariable UUID signalId,
            @RequestHeader(value = "X-User-Id") UUID userId) {

        SignalExecutionFallbackService.SignalExecutionSummary summary =
                fallbackService.getExecutionSummary(signalId, userId);

        if (summary == null) {
            return Map.of("error", "Signal track not found", "signalId", signalId.toString());
        }

        return Map.of(
                "signalId", summary.signalId().toString(),
                "strategyKey", summary.strategyKey(),
                "symbol", summary.symbol(),
                "signalType", summary.signalType(),
                "status", summary.status(),
                "executionMode", summary.executionMode(),
                "broker", Map.of(
                        "vendor", summary.brokerVendor() != null ? summary.brokerVendor() : "N/A",
                        "brokerOrderId", summary.brokerOrderId() != null ? summary.brokerOrderId() : "PENDING"
                ),
                "order", Map.of(
                        "orderId", summary.orderId() != null ? summary.orderId().toString() : "NOT_CREATED",
                        "side", summary.side() != null ? summary.side() : "N/A",
                        "quantity", summary.quantity() != null ? summary.quantity().toPlainString() : "0",
                        "entryPrice", summary.entryPrice() != null ? summary.entryPrice().toPlainString() : "PENDING"
                ),
                "execution", Map.of(
                        "currentStep", summary.currentStep() != null ? summary.currentStep() : "WAITING",
                        "executionTimeMs", summary.executionTimeMs() != null ? summary.executionTimeMs() : 0,
                        "createdAt", summary.createdAt().toString(),
                        "filledAt", summary.filledAt() != null ? summary.filledAt().toString() : "PENDING",
                        "updatedAt", summary.updatedAt().toString()
                ),
                "failure", summary.failureReason() != null ? Map.of(
                        "reason", summary.failureReason(),
                        "error", summary.lastError() != null ? summary.lastError() : "N/A",
                        "retryCount", summary.retryCount() != null ? summary.retryCount() : 0,
                        "nextAttempt", "AUTO-RETRY ENABLED"
                ) : null
        );
    }

    /**
     * Get recent signal execution history for a trader.
     * Shows execution flow and status updates.
     */
    @GetMapping("/history")
    public Map<String, Object> getSignalHistory(
            @RequestHeader(value = "X-User-Id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Instant since = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        Pageable pageable = PageRequest.of(page, size);
        Page<SignalExecutionTrack> tracks = trackRepository.findUserSignalsRecent(userId, since, pageable);

        List<Map<String, Object>> signals = tracks.getContent().stream()
                .map(track -> Map.of(
                        "signalId", track.getSignalId().toString(),
                        "symbol", track.getSymbol(),
                        "strategyKey", track.getStrategyKey(),
                        "status", track.getStatus().name(),
                        "executionMode", track.getExecutionMode() != null ? track.getExecutionMode() : "N/A",
                        "broker", track.getBrokerVendor() != null ? track.getBrokerVendor() : "PENDING",
                        "brokerOrderId", track.getBrokerOrderId() != null ? track.getBrokerOrderId() : "N/A",
                        "side", track.getSide() != null ? track.getSide() : "N/A",
                        "quantity", track.getQuantity() != null ? track.getQuantity().toPlainString() : "0",
                        "createdAt", track.getCreatedAt().toString(),
                        "filledAt", track.getFilledAt() != null ? track.getFilledAt().toString() : null,
                        "executionTimeMs", track.getExecutionTimeMs() != null ? track.getExecutionTimeMs() : null,
                        "currentStep", track.getCurrentStep(),
                        "failureReason", track.getFailureReason(),
                        "retries", track.getRetryCount()
                ))
                .collect(Collectors.toList());

        return Map.of(
                "userId", userId.toString(),
                "period", "Last 24 hours",
                "totalSignals", tracks.getTotalElements(),
                "currentPage", page,
                "totalPages", tracks.getTotalPages(),
                "signals", signals
        );
    }

    /**
     * Get execution dashboard with statistics.
     */
    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard(
            @RequestHeader(value = "X-User-Id") UUID userId) {

        Instant since24h = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        Instant since7d = Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);

        long filled24h = trackRepository.countFilledSignals(userId, since24h);
        long failed24h = trackRepository.countFailedSignals(userId, since24h);
        long filled7d = trackRepository.countFilledSignals(userId, since7d);
        long failed7d = trackRepository.countFailedSignals(userId, since7d);

        List<SignalExecutionTrack> pending = trackRepository.findPendingByStrategy(null);
        List<SignalExecutionTrack> retryable = trackingService.getRetryableFailures(3, 2);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("period24h", Map.of(
                "filled", filled24h,
                "failed", failed24h,
                "successRate", filled24h + failed24h > 0 ?
                        String.format("%.1f%%", (filled24h * 100.0) / (filled24h + failed24h)) : "0%"
        ));
        stats.put("period7d", Map.of(
                "filled", filled7d,
                "failed", failed7d,
                "successRate", filled7d + failed7d > 0 ?
                        String.format("%.1f%%", (filled7d * 100.0) / (filled7d + failed7d)) : "0%"
        ));
        stats.put("pending", Map.of(
                "count", pending.size(),
                "strategies", pending.stream()
                        .map(SignalExecutionTrack::getStrategyKey)
                        .distinct()
                        .collect(Collectors.toList())
        ));
        stats.put("retryable", Map.of(
                "count", retryable.size(),
                "status", "AUTO-RETRY SCHEDULED"
        ));

        return Map.of(
                "userId", userId.toString(),
                "timestamp", Instant.now().toString(),
                "stats", stats,
                "brokerInfo", Map.of(
                        "primary", "ZERODHA",
                        "connection", "LIVE",
                        "lastSync", Instant.now().toString()
                )
        );
    }

    /**
     * Get pending signals waiting for execution.
     */
    @GetMapping("/pending")
    public Map<String, Object> getPendingSignals(
            @RequestParam(required = false) String strategyKey) {

        List<SignalExecutionTrack> pending = trackingService.getPendingByStrategy(
                strategyKey != null ? strategyKey : "%");

        List<Map<String, Object>> signals = pending.stream()
                .map(track -> Map.of(
                        "signalId", track.getSignalId().toString(),
                        "symbol", track.getSymbol(),
                        "strategyKey", track.getStrategyKey(),
                        "side", track.getSide() != null ? track.getSide() : "N/A",
                        "quantity", track.getQuantity() != null ? track.getQuantity().toPlainString() : "0",
                        "status", track.getStatus().name(),
                        "currentStep", track.getCurrentStep(),
                        "executionMode", track.getExecutionMode() != null ? track.getExecutionMode() : "N/A",
                        "broker", track.getBrokerVendor() != null ? track.getBrokerVendor() : "PENDING",
                        "createdAt", track.getCreatedAt().toString(),
                        "waitingTimeMs", java.time.temporal.ChronoUnit.MILLIS.between(
                                track.getCreatedAt(), Instant.now())
                ))
                .collect(Collectors.toList());

        return Map.of(
                "strategyFilter", strategyKey != null ? strategyKey : "ALL",
                "pendingCount", signals.size(),
                "signals", signals
        );
    }

    /**
     * Get signals requiring manual intervention.
     */
    @GetMapping("/issues")
    public Map<String, Object> getIssueSignals(
            @RequestHeader(value = "X-User-Id") UUID userId) {

        Instant since = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        Page<SignalExecutionTrack> tracks = trackRepository.findUserSignalsRecent(
                userId, since, PageRequest.of(0, 100));

        List<Map<String, Object>> issues = tracks.getContent().stream()
                .filter(t -> t.getStatus().name().contains("FAILED"))
                .map(track -> Map.of(
                        "signalId", track.getSignalId().toString(),
                        "symbol", track.getSymbol(),
                        "strategyKey", track.getStrategyKey(),
                        "failureReason", track.getFailureReason(),
                        "errorDetail", track.getLastError() != null ? track.getLastError() : "N/A",
                        "status", track.getStatus().name(),
                        "retryCount", track.getRetryCount(),
                        "suggestion", suggestFix(track.getFailureReason()),
                        "createdAt", track.getCreatedAt().toString()
                ))
                .collect(Collectors.toList());

        return Map.of(
                "issueCount", issues.size(),
                "issues", issues
        );
    }

    private String suggestFix(String failureReason) {
        if (failureReason == null) return "UNKNOWN";
        if (failureReason.contains("LIVE_GATE")) return "Enable live trading in preferences";
        if (failureReason.contains("SIZING_REJECTED")) return "Check position size limits";
        if (failureReason.contains("RISK_REJECTED")) return "Review risk parameters";
        if (failureReason.contains("EXPOSURE")) return "Check exposure limits";
        if (failureReason.contains("BROKER_TRUTH")) return "Sync broker positions and retry";
        return "Contact support - " + failureReason;
    }
}
