package com.stokr.strategy.telemetry;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process scanner evaluation telemetry (mean-reversion poll loop and similar).
 */
@Service
public class ScannerExecutionTelemetryService {

    private final LongAdder evaluations = new LongAdder();
    private final LongAdder signalsEmitted = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final AtomicLong lastDurationNanos = new AtomicLong();
    private final AtomicInteger lastSymbolCount = new AtomicInteger();

    private final AtomicLong lastPollWallNanos = new AtomicLong();
    private final AtomicInteger lastPollSymbolsScanned = new AtomicInteger();
    private final AtomicInteger lastPollSignalsEmitted = new AtomicInteger();
    private final AtomicInteger lastPollFailures = new AtomicInteger();
    private final AtomicLong lastPollEpochMillis = new AtomicLong();
    private final AtomicBoolean lastPollWasSkipped = new AtomicBoolean();
    private final AtomicReference<String> lastPollSkipReason = new AtomicReference<>();

    public void recordEvaluationComplete(String symbol, long durationNanos, boolean signalEmitted, Throwable error) {
        evaluations.increment();
        lastDurationNanos.set(Math.max(0L, durationNanos));
        lastSymbolCount.set(1);
        if (signalEmitted) {
            signalsEmitted.increment();
        }
        if (error != null) {
            failures.increment();
        }
    }

    /**
     * Aggregates one scheduler poll pass (all symbols).
     */
    public void recordPollCycleFinished(
            Instant pollCompletedAt,
            int symbolsScanned,
            int signalsThisCycle,
            int failuresThisCycle,
            long wallClockNanos
    ) {
        lastPollWasSkipped.set(false);
        lastPollSkipReason.set(null);
        if (pollCompletedAt != null) {
            lastPollEpochMillis.set(pollCompletedAt.toEpochMilli());
        }
        lastPollSymbolsScanned.set(Math.max(0, symbolsScanned));
        lastPollSignalsEmitted.set(Math.max(0, signalsThisCycle));
        lastPollFailures.set(Math.max(0, failuresThisCycle));
        lastPollWallNanos.set(Math.max(0L, wallClockNanos));
    }

    /**
     * When the scheduler intentionally skips a poll (e.g. platform market path offline).
     */
    public void recordPollSkipped(Instant at, String reason) {
        lastPollWasSkipped.set(true);
        lastPollSkipReason.set(reason != null ? reason : "unspecified");
        if (at != null) {
            lastPollEpochMillis.set(at.toEpochMilli());
        }
        lastPollSymbolsScanned.set(0);
        lastPollSignalsEmitted.set(0);
        lastPollFailures.set(0);
        lastPollWallNanos.set(0L);
    }

    public Map<String, Object> snapshotOverlay(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evaluationsTotal", evaluations.sum());
        m.put("signalsFromScannerTotal", signalsEmitted.sum());
        m.put("failuresTotal", failures.sum());
        long d = lastDurationNanos.get();
        m.put("lastScanDurationMs", d > 0 ? d / 1_000_000.0 : null);
        m.put("lastSymbolBatchSize", lastSymbolCount.get());
        long pollMs = lastPollWallNanos.get();
        m.put("lastPollCompletedAt", lastPollEpochMillis.get() > 0
                ? Instant.ofEpochMilli(lastPollEpochMillis.get()).toString()
                : null);
        m.put("lastPollSymbolsScanned", lastPollSymbolsScanned.get());
        m.put("lastPollSignalsEmitted", lastPollSignalsEmitted.get());
        m.put("lastPollFailures", lastPollFailures.get());
        m.put("lastPollWallDurationMs", pollMs > 0 ? pollMs / 1_000_000.0 : null);
        m.put("lastPollWasSkipped", lastPollWasSkipped.get());
        m.put("lastPollSkipReason", lastPollSkipReason.get());
        m.put("collectedAt", now.toString());
        m.put("note", "Counters are JVM-local; restart clears. Poll metrics reflect the most recent scheduler cycle.");
        return m;
    }
}
