package com.stokr.strategy.sdk;

/**
 * Opaque restore envelope ??? executor fills from {@code StrategyStateSnapshot}.
 */
public record StrategyRestoreContext(
        String stateJson,
        String indicatorJson,
        String replayCheckpointRef,
        long snapshotSequence
) {
}
