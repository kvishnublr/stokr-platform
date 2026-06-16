package com.stokr.strategy.dto;

import java.util.UUID;

/**
 * Opaque snapshot bundle restored before deterministic continuation after restart.
 */
public record StrategyRestoreBundle(
        String stateJson,
        String indicatorJson,
        String replayCheckpointRef,
        long snapshotSequence,
        UUID snapshotId
) {
}
