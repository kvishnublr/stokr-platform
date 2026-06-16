package com.stokr.admin.signal;

import java.util.List;
import java.util.Map;

public record AdminProtectionDiagnosticsDto(
        String windowStart,
        String windowEnd,
        long totalProtectionExits,
        long prematureVolumeVacuumExits,
        long minHoldBypassedCount,
        double avgHoldSeconds,
        Map<String, Long> exitsByCategory,
        List<ProtectionExitRow> recentExits
) {
    public record ProtectionExitRow(
            String signalId,
            String strategyName,
            String symbol,
            long holdSeconds,
            String exitCategory,
            String exitReason,
            boolean minHoldBypassed,
            String pressureTrigger
    ) {
    }
}
