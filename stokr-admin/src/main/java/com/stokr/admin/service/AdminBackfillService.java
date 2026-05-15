package com.stokr.admin.service;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.engine.BacktestReplayJobRunner;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminBackfillService {

    private final BacktestJobRepository backtestJobRepository;
    private final BacktestReplayJobRunner backtestReplayJobRunner;

    @Transactional(readOnly = true)
    public List<BacktestJob> recentJobs(int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<BacktestJob> rows = backtestJobRepository.findTop15ByDeletedFalseOrderByUpdatedAtDesc();
        return rows.stream().limit(n).toList();
    }

    @Transactional
    public void cancel(UUID jobId) {
        BacktestJob j = backtestJobRepository.findById(jobId)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        if (j.getStatus() == BacktestJobStatus.COMPLETED || j.getStatus() == BacktestJobStatus.CANCELLED) {
            return;
        }
        j.setCancelled(true);
        if (j.getStatus() == BacktestJobStatus.QUEUED) {
            j.setStatus(BacktestJobStatus.CANCELLED);
            j.setMessage("Cancelled by admin before start");
        } else {
            j.setMessage("Admin cancellation requested");
        }
        backtestJobRepository.save(j);
    }

    @Transactional
    public UUID rerun(UUID jobId) {
        BacktestJob src = backtestJobRepository.findById(jobId)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        if (src.getRequestJson() == null || src.getRequestJson().isBlank()) {
            throw new BadRequestException("Cannot rerun: request payload missing");
        }
        BacktestJob j = new BacktestJob();
        j.setUserId(src.getUserId());
        j.setRequestJson(src.getRequestJson());
        j.setStatus(BacktestJobStatus.QUEUED);
        j.setProgress(0);
        j.setProcessedBars(0);
        j.setTotalBars(0);
        j.setCancelled(false);
        j.setMessage("Queued by admin rerun");
        j.setCorrelationId("admin-rerun-" + Instant.now().toEpochMilli());
        j.setMetadataSchemaVersion(src.getMetadataSchemaVersion());
        j.setStrategyDefinitionVersion(src.getStrategyDefinitionVersion());
        j = backtestJobRepository.save(j);
        UUID newId = j.getId();
        backtestReplayJobRunner.run(newId);
        return newId;
    }
}

