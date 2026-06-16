package com.stokr.strategy.sdk.context;

import java.util.UUID;

public record ReplayContext(
        UUID backtestRunId,
        boolean replayMode,
        String stepTimeframe,
        int candleIndex,
        int totalBars
) {
}
