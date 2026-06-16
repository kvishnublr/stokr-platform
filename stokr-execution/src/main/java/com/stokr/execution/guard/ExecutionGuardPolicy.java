package com.stokr.execution.guard;

import java.math.BigDecimal;

public record ExecutionGuardPolicy(
        long maxSignalAgeMs,
        BigDecimal maxDriftPct,
        BigDecimal maxSpreadPct,
        BigDecimal maxSlippagePct,
        long softStaleFeedMs,
        long hardStaleFeedMs,
        BigDecimal volatilityThresholdPct,
        BigDecimal minLiquidityQty,
        boolean allowExitDuringStaleFeed,
        boolean allowSoftFailExecution
) {
}

