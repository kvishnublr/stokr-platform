package com.stokr.backtest.service;

import com.stokr.backtest.engine.ReplayProgressCallback;
import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Throttles DB + WebSocket fan-out during long replays.
 */
public final class BacktestJobReplayProgressTracker implements ReplayProgressCallback {

    private static final int MIN_BAR_DELTA = 50;
    private static final long MIN_PUBLISH_MS = 2000L;

    private final UUID jobId;
    private final UUID userId;
    private final BacktestJobStatusWriter statusWriter;
    private final ApplicationEventPublisher eventPublisher;

    private UUID runId;
    private Instant startedAtSnapshot;
    private int lastPublishedBars;
    private long lastPublishEpochMs;

    public BacktestJobReplayProgressTracker(
            UUID jobId,
            UUID userId,
            BacktestJobStatusWriter statusWriter,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jobId = jobId;
        this.userId = userId;
        this.statusWriter = statusWriter;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onRunPersisted(UUID runId) {
        this.runId = runId;
    }

    @Override
    public void onTotalsKnown(UUID runId, int totalBars) {
        this.runId = runId;
        statusWriter.recordRunAndTotals(jobId, runId, totalBars);
        this.startedAtSnapshot = Instant.now();
    }

    @Override
    public boolean isCancelled() {
        return statusWriter.isJobCancelled(jobId);
    }

    @Override
    public void onBarProgress(UUID runId, int barsCompleted, int totalBars) {
        if (runId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean dueBars = barsCompleted - lastPublishedBars >= MIN_BAR_DELTA;
        boolean dueTime = now - lastPublishEpochMs >= MIN_PUBLISH_MS;
        boolean terminal = barsCompleted >= totalBars && totalBars > 0;
        if (!dueBars && !dueTime && !terminal) {
            return;
        }
        lastPublishedBars = barsCompleted;
        lastPublishEpochMs = now;
        statusWriter.updateProgress(jobId, barsCompleted, totalBars);
        int pct = totalBars <= 0 ? 0 : (int) Math.min(100L, (barsCompleted * 100L) / totalBars);
        Long eta = computeEtaSeconds(startedAtSnapshot, barsCompleted, totalBars);
        eventPublisher.publishEvent(new RealtimeBridgeEvents.BacktestJobProgress(
                userId,
                jobId,
                runId,
                barsCompleted,
                totalBars,
                pct,
                eta,
                "RUNNING"
        ));
    }

    private static Long computeEtaSeconds(Instant started, int done, int total) {
        if (started == null || done <= 0 || total <= done) {
            return null;
        }
        long elapsed = Duration.between(started, Instant.now()).getSeconds();
        if (elapsed <= 0) {
            return null;
        }
        double rate = (double) done / (double) elapsed;
        if (rate <= 0.000_001) {
            return null;
        }
        return Math.max(0L, Math.round((total - done) / rate));
    }
}
