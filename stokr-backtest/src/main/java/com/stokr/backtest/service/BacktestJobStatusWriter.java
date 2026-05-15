package com.stokr.backtest.service;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.domain.ReplayTerminalDiagnosis;
import com.stokr.backtest.repository.BacktestJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short REQUIRES_NEW transactions so long-running replay does not hold DB locks.
 */
@Service
@RequiredArgsConstructor
public class BacktestJobStatusWriter {

    private final BacktestJobRepository jobRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<UUID, Integer> lastReplaySsePct = new ConcurrentHashMap<>();

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isJobCancelled(UUID jobId) {
        return jobRepository.findById(jobId).map(BacktestJob::isCancelled).orElse(true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markQueuedCancelled(UUID jobId, String message) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setCancelled(true);
        job.setStatus(BacktestJobStatus.CANCELLED);
        job.setMessage(truncate(message, 4000));
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID jobId) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        if (job.isDeleted()) {
            return;
        }
        job.setStatus(BacktestJobStatus.RUNNING);
        job.setProgress(0);
        job.setProcessedBars(0);
        job.setStartedAt(Instant.now());
        job.setMessage(null);
        job.setReplayDiagnosis(null);
        job.setReplayCandlesExpected(0);
        job.setReplayCandlesProcessed(0);
        job.setReplaySignalsEmitted(0);
        job.setReplayExecutionEvents(0);
        job.setReplayDurationMs(null);
        jobRepository.save(job);
        publishReplay("replay_running", jobId, 0, "RUNNING");
    }

    /**
     * Persists backtest run id and bar total once the replay row exists (before candle loop).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRunAndTotals(UUID jobId, UUID runId, int totalBars) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setRunId(runId);
        job.setTotalBars(totalBars);
        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID jobId, int barsCompleted, int totalBars) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        int safeTotal = Math.max(1, totalBars);
        int clamped = Math.min(barsCompleted, safeTotal);
        int pct = (int) Math.min(100L, (clamped * 100L) / safeTotal);
        job.setProcessedBars(clamped);
        job.setProgress(pct);
        jobRepository.save(job);
        maybePublishReplayProgress(jobId, pct);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID jobId, BacktestReplayOutcome outcome) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(BacktestJobStatus.COMPLETED);
        job.setRunId(outcome.runId());
        job.setProgress(100);
        if (job.getTotalBars() > 0) {
            job.setProcessedBars(job.getTotalBars());
        }
        job.setMessage(null);
        ReplayTerminalDiagnosis diagnosis = ReplayDiagnosisClassifier.classifySuccess(outcome);
        job.setReplayDiagnosis(diagnosis.name());
        ReplayLoopTelemetry lt = outcome.loopTelemetry();
        if (lt != null) {
            job.setReplayCandlesExpected(lt.candlesExpected());
            job.setReplayCandlesProcessed(lt.candlesProcessed());
            job.setReplaySignalsEmitted(lt.signalsEmitted());
            job.setReplayDurationMs(lt.durationMs(Instant.now()));
        } else {
            job.setReplayCandlesExpected(Math.max(job.getTotalBars(), 0));
            job.setReplayCandlesProcessed(Math.max(job.getProcessedBars(), 0));
            job.setReplaySignalsEmitted(0);
            job.setReplayDurationMs(null);
        }
        if (outcome.validation() != null) {
            long ex = outcome.validation().executionEventCount();
            job.setReplayExecutionEvents((int) Math.min(Integer.MAX_VALUE, ex));
        } else {
            job.setReplayExecutionEvents(0);
        }
        jobRepository.save(job);
        publishReplay("replay_completed", jobId, 100, "COMPLETED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String message) {
        markFailed(jobId, message, ReplayTerminalDiagnosis.FAILED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String message, ReplayTerminalDiagnosis diagnosis) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(BacktestJobStatus.FAILED);
        job.setMessage(truncate(message, 4000));
        if (diagnosis != null) {
            job.setReplayDiagnosis(diagnosis.name());
        }
        jobRepository.save(job);
        publishReplay("replay_failed", jobId, job.getProgress(), "FAILED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelledAfterReplay(UUID jobId, UUID runId, String message) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setCancelled(true);
        job.setStatus(BacktestJobStatus.CANCELLED);
        if (runId != null) {
            job.setRunId(runId);
        }
        job.setMessage(truncate(message, 4000));
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInterruptedByRestart(UUID jobId) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        if (job.getStatus() != BacktestJobStatus.RUNNING) {
            return;
        }
        job.setStatus(BacktestJobStatus.FAILED);
        job.setMessage(truncate("Server restarted while job was running — resume the linked backtest run if needed", 4000));
        jobRepository.save(job);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void maybePublishReplayProgress(UUID jobId, int pct) {
        if (pct <= 0 || pct >= 100) {
            return;
        }
        if (pct % 10 != 0) {
            return;
        }
        Integer prev = lastReplaySsePct.put(jobId, pct);
        if (prev != null && prev >= pct) {
            return;
        }
        publishReplay("replay_progress", jobId, pct, "RUNNING");
    }

    private void publishReplay(String topic, UUID jobId, int progressPct, String status) {
        eventPublisher.publishEvent(new OperationalRealtimeEvent(topic, Map.of(
                "jobId", jobId.toString(),
                "progressPct", progressPct,
                "status", status
        )));
    }
}
