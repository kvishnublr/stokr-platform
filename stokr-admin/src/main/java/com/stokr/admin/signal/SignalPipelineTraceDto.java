package com.stokr.admin.signal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SignalPipelineTraceDto(
    UUID signalId,
    String symbol,
    String strategyKey,
    String signalType,
    String executionMode,
    String outcomeStatus,
    Instant createdAt,
    String overallStatus,
    List<PipelineStageDto> applicationPipeline,
    List<UserTraceDto> users
) {

    public record PipelineStageDto(
        String stage,
        String status,
        String label,
        Instant timestamp,
        String rejectionCode,
        String rejectionMessage,
        Map<String, Object> details,
        int orderIndex
    ) {}

    public record UserTraceDto(
        UUID userId,
        String username,
        String displayName,
        String finalStatus,
        String lastStage,
        String lastRejectionCode,
        String lastRejectionMessage,
        String brokerExternalOrderId,
        UUID brokerOrderId,
        List<PipelineStageDto> userStages
    ) {}
}
