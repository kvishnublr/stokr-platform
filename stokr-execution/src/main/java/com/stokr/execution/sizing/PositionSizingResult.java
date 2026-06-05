package com.stokr.execution.sizing;

import com.stokr.oms.domain.ExecutionMode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record PositionSizingResult(
        boolean accepted,
        BigDecimal quantity,
        BigDecimal normalizedQuantity,
        BigDecimal exposureValue,
        BigDecimal capitalUsed,
        BigDecimal availableCapitalBefore,
        BigDecimal availableCapitalAfter,
        BigDecimal utilizationPct,
        SizingMode sizingMode,
        String rejectedReason,
        String snapshotHash,
        Map<String, Object> snapshot,
        String brokerNormalizationNote) {

    public static PositionSizingResult rejected(String reason, SizingMode mode, Map<String, Object> snapshot, String hash) {
        return new PositionSizingResult(
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                mode,
                reason,
                hash,
                snapshot,
                null);
    }

    public static PositionSizingResult accepted(
            BigDecimal qty,
            BigDecimal normalizedQty,
            BigDecimal exposure,
            BigDecimal capitalUsed,
            BigDecimal availBefore,
            BigDecimal availAfter,
            BigDecimal utilization,
            SizingMode mode,
            Map<String, Object> snapshot,
            String hash,
            String brokerNote) {
        return new PositionSizingResult(
                true,
                qty,
                normalizedQty,
                exposure,
                capitalUsed,
                availBefore,
                availAfter,
                utilization,
                mode,
                null,
                hash,
                snapshot,
                brokerNote);
    }

    public static PositionSizingResult acceptedForExit(BigDecimal qty) {
        return new PositionSizingResult(
                true,
                qty,
                qty,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SizingMode.FIXED_QUANTITY,
                null,
                "exit_signal",
                Map.of("signalType", "EXIT"),
                "exit_bypass");
    }
}
