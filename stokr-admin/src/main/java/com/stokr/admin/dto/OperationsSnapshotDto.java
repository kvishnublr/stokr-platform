package com.stokr.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase-1 aggregated operational telemetry for the admin cockpit.
 * Sections evolve as instrumentation is added; unknown fields stay explicit rather than decorative.
 */
public record OperationsSnapshotDto(
        Instant collectedAt,
        Map<String, Object> marketInfra,
        Map<String, Object> replayInfra,
        Map<String, Object> oms,
        Map<String, Object> system,
        Map<String, Object> brokerSessions,
        Map<String, Object> marketFreshness,
        Map<String, Object> scannerTelemetry,
        Map<String, Object> signalDistribution,
        Map<String, Object> traderExecutionHealth,
        List<Map<String, Object>> incidents,
        Map<String, Object> marketPlane,
        Map<String, Object> operationalHistory
) {
}
