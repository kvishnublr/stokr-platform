package com.stokr.execution.guard;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExecutionAttemptKey(
        UUID signalId,
        String symbol,
        String side,
        String strategyKey,
        String timeframe,
        long bucketEpochSecond
) {
    public static ExecutionAttemptKey of(
            UUID signalId,
            String symbol,
            String side,
            String strategyKey,
            String timeframe,
            Instant now,
            long bucketSeconds
    ) {
        long b = Math.max(1L, bucketSeconds);
        long bucket = (now.getEpochSecond() / b) * b;
        return new ExecutionAttemptKey(
                signalId,
                normalize(symbol),
                normalize(side),
                normalize(strategyKey),
                normalize(timeframe),
                bucket
        );
    }

    private static String normalize(String v) {
        return v == null ? "" : v.trim().toUpperCase();
    }

    @Override
    public String toString() {
        return String.join("|",
                signalId == null ? "NO_SIGNAL" : signalId.toString(),
                Objects.toString(symbol, ""),
                Objects.toString(side, ""),
                Objects.toString(strategyKey, ""),
                Objects.toString(timeframe, ""),
                String.valueOf(bucketEpochSecond)
        );
    }
}

