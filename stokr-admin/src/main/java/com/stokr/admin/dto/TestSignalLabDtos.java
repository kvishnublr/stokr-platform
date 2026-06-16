package com.stokr.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TestSignalLabDtos {

    private TestSignalLabDtos() {
    }

    public record TestSignalLabRequest(
            @NotNull UUID traderUserId,
            UUID brokerAccountId,
            @NotBlank String strategyKey,
            String strategyTemplate,
            @NotBlank String symbol,
            @NotBlank String side,
            BigDecimal quantity,
            String productType,
            String orderType,
            @NotBlank String executionMode,
            String exchange,
            BigDecimal price,
            String triggerType,
            boolean forceQuantityOne,
            boolean dryRunOnly,
            boolean skipActualBrokerExecution,
            boolean simulateRejection,
            boolean simulateTimeout,
            boolean simulateStaleWebsocket,
            boolean simulateMarginFailure,
            boolean simulateBrokerDisconnect,
            Integer autoSquareOffMinutes
    ) {
    }

    public record TestSignalCheckResult(
            String key,
            String label,
            String status,
            String message,
            String suggestedAction,
            String actionCode
    ) {
    }

    public record TestSignalTimelineEvent(
            String stage,
            Instant at,
            String detail
    ) {
    }

    public record TestSignalExecutionReport(
            UUID testId,
            String status,
            String finalStatus,
            UUID signalId,
            UUID orderId,
            Long totalLatencyMs,
            Map<String, Object> summary,
            List<TestSignalTimelineEvent> timeline,
            List<TestSignalCheckResult> checks,
            Map<String, Object> healthSnapshot,
            Map<String, Object> diagnostics
    ) {
    }

    public record TestSignalPreflightReport(
            boolean canSubmit,
            String effectiveExecutionMode,
            List<String> blockers,
            List<TestSignalCheckResult> checks
    ) {
    }

    public record TestSignalRunSummaryDto(
            UUID id,
            Instant createdAt,
            UUID traderUserId,
            String strategyKey,
            String symbol,
            String side,
            BigDecimal quantity,
            String executionMode,
            String status,
            String finalStatus,
            UUID signalId,
            UUID orderId,
            String squareOffStatus
    ) {
    }
}
