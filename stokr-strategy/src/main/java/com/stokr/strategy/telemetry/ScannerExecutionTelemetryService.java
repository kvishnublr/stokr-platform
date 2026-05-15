package com.stokr.strategy.telemetry;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    public Map<String, Object> snapshotOverlay(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evaluationsTotal", evaluations.sum());
        m.put("signalsFromScannerTotal", signalsEmitted.sum());
        m.put("failuresTotal", failures.sum());
        long d = lastDurationNanos.get();
        m.put("lastScanDurationMs", d > 0 ? d / 1_000_000.0 : null);
        m.put("lastSymbolBatchSize", lastSymbolCount.get());
        m.put("collectedAt", now.toString());
        m.put("note", "Counters are JVM-local; restart clears. Wired from mean-reversion evaluation loop.");
        return m;
    }
}
