package com.stokr.common.telemetry;

import com.stokr.common.events.ExecutionDispatchFailedEvent;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process signal routing / execution telemetry for admin ops (not durable — survives process lifetime only).
 */
@Service
public class SignalDistributionTelemetryService {

    private static final int RECENT_SIGNALS = 40;
    private static final int LATENCY_SAMPLES = 32;

    private final LongAdder signalsEmitted = new LongAdder();
    private final LongAdder rabbitDispatches = new LongAdder();
    private final LongAdder omsIntentsSeen = new LongAdder();
    private final LongAdder ordersCreatedFromSignal = new LongAdder();
    private final LongAdder gateRejected = new LongAdder();
    private final LongAdder riskRejected = new LongAdder();
    private final LongAdder executionFailures = new LongAdder();
    private final LongAdder executionRetries = new LongAdder();
    private final LongAdder executionDlq = new LongAdder();
    private final LongAdder idempotentHits = new LongAdder();

    private final AtomicLong[] routingLatencyNanos = new AtomicLong[LATENCY_SAMPLES];
    private final AtomicInteger routingLatencyIdx = new AtomicInteger();

    private final List<Map<String, Object>> recentSignals = new ArrayList<>();

    private final ConcurrentHashMap<UUID, UserAgg> byUser = new ConcurrentHashMap<>();

    public SignalDistributionTelemetryService() {
        for (int i = 0; i < LATENCY_SAMPLES; i++) {
            routingLatencyNanos[i] = new AtomicLong();
        }
    }

    @EventListener
    public void onSignalPublished(SignalPublishedEvent e) {
        signalsEmitted.increment();
        synchronized (recentSignals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", Instant.now().toString());
            row.put("signalId", e.signalId().toString());
            row.put("userId", e.userId().toString());
            row.put("symbol", e.symbol());
            row.put("strategyKey", e.strategyKey());
            recentSignals.add(0, row);
            while (recentSignals.size() > RECENT_SIGNALS) {
                recentSignals.remove(recentSignals.size() - 1);
            }
        }
        agg(e.userId()).signalsPublished.increment();
    }

    @EventListener
    public void onExecutionDispatchFailed(ExecutionDispatchFailedEvent e) {
        if (e.userId() != null) {
            recordExecutionFailure(e.userId(), e.signalId());
        }
    }

    public void recordPipelineDispatchNanos(long nanos) {
        rabbitDispatches.increment();
        int i = Math.floorMod(routingLatencyIdx.getAndIncrement(), LATENCY_SAMPLES);
        routingLatencyNanos[i].set(Math.max(0L, nanos));
    }

    public void recordOmsIntentReceived(SignalPersistedMessage msg) {
        omsIntentsSeen.increment();
        if (msg.userId() != null) {
            agg(msg.userId()).omsIntents.increment();
        }
    }

    public void recordOrderCreatedFromSignal(UUID userId, UUID orderId, UUID signalId) {
        ordersCreatedFromSignal.increment();
        UserAgg u = agg(userId);
        u.ordersCreated.increment();
        u.lastSignalId = signalId;
    }

    public void recordGateRejected(UUID userId, UUID signalId, String phase) {
        gateRejected.increment();
        UserAgg u = agg(userId);
        u.gateRejects.increment();
        u.lastSignalId = signalId;
        u.lastRejectPhase = phase;
    }

    public void recordRiskRejected(UUID userId, UUID signalId) {
        riskRejected.increment();
        UserAgg u = agg(userId);
        u.riskRejects.increment();
        u.lastSignalId = signalId;
    }

    public void recordIdempotentHit(UUID userId) {
        idempotentHits.increment();
        agg(userId).idempotentHits.increment();
    }

    public void recordExecutionFailure(UUID userId, UUID signalId) {
        executionFailures.increment();
        UserAgg u = agg(userId);
        u.executionFails.increment();
        u.lastSignalId = signalId;
    }

    public void recordExecutionRetry() {
        executionRetries.increment();
    }

    public void recordExecutionDlq() {
        executionDlq.increment();
    }

    public Map<String, Object> snapshotOverlay() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signalsEmittedTotal", signalsEmitted.sum());
        m.put("rabbitDispatchesTotal", rabbitDispatches.sum());
        m.put("omsIntentsSeenTotal", omsIntentsSeen.sum());
        m.put("ordersCreatedFromSignalTotal", ordersCreatedFromSignal.sum());
        m.put("gateRejectedTotal", gateRejected.sum());
        m.put("riskRejectedTotal", riskRejected.sum());
        m.put("executionFailuresTotal", executionFailures.sum());
        m.put("executionRetriesTotal", executionRetries.sum());
        m.put("executionDlqTotal", executionDlq.sum());
        m.put("idempotentHitsTotal", idempotentHits.sum());
        m.put("routingLatencyMsAvgSample", avgRoutingLatencyMs());
        m.put("recentSignalEvents", copyRecent());
        m.put("perTraderDelivery", perTraderRows());
        m.put("model", "IN_PROCESS_RING_BUFFER");
        m.put("note", "Telemetry is in-process only — restarts clear counters; extend with DB journal for audit-grade routing.");
        return m;
    }

    private List<Map<String, Object>> copyRecent() {
        synchronized (recentSignals) {
            return new ArrayList<>(recentSignals);
        }
    }

    private Double avgRoutingLatencyMs() {
        long sum = 0;
        int n = 0;
        for (AtomicLong bucket : routingLatencyNanos) {
            long v = bucket.get();
            if (v > 0) {
                sum += v;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return (sum / (double) n) / 1_000_000.0;
    }

    private List<Map<String, Object>> perTraderRows() {
        List<UserAgg> list = new ArrayList<>(byUser.values());
        list.sort(Comparator.comparingLong((UserAgg u) -> u.activity()).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        int cap = 0;
        for (UserAgg u : list) {
            if (++cap > 20) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", u.userId.toString());
            row.put("signalsPublished", u.signalsPublished.sum());
            row.put("omsIntents", u.omsIntents.sum());
            row.put("ordersCreated", u.ordersCreated.sum());
            row.put("gateRejects", u.gateRejects.sum());
            row.put("riskRejects", u.riskRejects.sum());
            row.put("executionFails", u.executionFails.sum());
            row.put("idempotentHits", u.idempotentHits.sum());
            row.put("deliveredToOms", u.omsIntents.sum() > 0);
            row.put("executedApprox", u.ordersCreated.sum() > 0);
            row.put("rejectedApprox", u.gateRejects.sum() + u.riskRejects.sum() > 0);
            row.put("lastSignalId", u.lastSignalId != null ? u.lastSignalId.toString() : null);
            row.put("lastRejectPhase", u.lastRejectPhase);
            out.add(row);
        }
        return out;
    }

    private static final UserAgg NO_OP_AGG = new UserAgg(null);

    private UserAgg agg(UUID userId) {
        if (userId == null) return NO_OP_AGG;
        return byUser.computeIfAbsent(userId, UserAgg::new);
    }

    private static final class UserAgg {
        private final UUID userId;
        private final LongAdder signalsPublished = new LongAdder();
        private final LongAdder omsIntents = new LongAdder();
        private final LongAdder ordersCreated = new LongAdder();
        private final LongAdder gateRejects = new LongAdder();
        private final LongAdder riskRejects = new LongAdder();
        private final LongAdder executionFails = new LongAdder();
        private final LongAdder idempotentHits = new LongAdder();
        private volatile UUID lastSignalId;
        private volatile String lastRejectPhase;

        private UserAgg(UUID userId) {
            this.userId = userId;
        }

        private long activity() {
            return signalsPublished.sum() + omsIntents.sum() + ordersCreated.sum() + gateRejects.sum() + riskRejects.sum()
                    + executionFails.sum();
        }
    }
}
