package com.stokr.bootstrap.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Snapshot gathered before classification and recovery action selection.
 */
public record OperationalRecoveryContext(
        Instant collectedAt,
        boolean actuatorHealthy,
        Map<String, Object> actuatorHealth,
        Map<String, Object> brokerFeed,
        Map<String, Object> feedHealth,
        Map<String, Object> redis,
        Map<String, Object> database,
        boolean killSwitchActive,
        Map<String, Object> killSwitchDetail,
        Map<String, Object> executionPipeline,
        Map<String, Object> scannerTelemetry,
        int activeScannerBindings,
        boolean requiresUserOAuth,
        boolean ingestionPausedByOperator,
        List<String> errorSignatures,
        List<String> recentLogLines
) {
}
