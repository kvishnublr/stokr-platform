package com.stokr.backtest.service;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.repository.BacktestJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Short REQUIRES_NEW transactions so long-running replay does not hold DB locks.
 */
@Service
@RequiredArgsConstructor
public class BacktestJobStatusWriter {

    private final BacktestJobRepository jobRepository;

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
        jobRepository.save(job);
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
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID jobId, UUID runId) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(BacktestJobStatus.COMPLETED);
        job.setRunId(runId);
        job.setProgress(100);
        if (job.getTotalBars() > 0) {
            job.setProcessedBars(job.getTotalBars());
        }
        job.setMessage(null);
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String message) {
        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(BacktestJobStatus.FAILED);
        job.setMessage(truncate(message, 4000));
        jobRepository.save(job);
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
}
