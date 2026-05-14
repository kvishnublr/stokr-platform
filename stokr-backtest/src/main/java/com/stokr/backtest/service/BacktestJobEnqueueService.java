package com.stokr.backtest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.engine.BacktestReplayJobRunner;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.backtest.validation.StrategyExecutionRequestValidator;
import com.stokr.backtest.web.dto.BacktestJobStatusDto;
import com.stokr.backtest.web.dto.ExecutionRequestDto;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class BacktestJobEnqueueService {

    private final BacktestJobRepository backtestJobRepository;
    private final StrategyExecutionRequestValidator strategyExecutionRequestValidator;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final ObjectMapper objectMapper;
    private final BacktestReplayJobRunner backtestReplayJobRunner;

    @Transactional
    public UUID enqueueReplayJob(UUID userId, ExecutionRequestDto request) {
        long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong();
        ExecutionRequestDto resolved = request.withResolvedSeed(seed);
        StrategyMetadataResponseDto meta = strategyExecutionRequestValidator.validateAndLoadMetadata(resolved);
        StrategyDefinition def = strategyDefinitionRepository
                .findByStrategyKeyAndDeletedFalse(resolved.strategyKey())
                .orElseThrow(() -> new NotFoundException("Strategy definition not found"));

        BacktestJob job = new BacktestJob();
        job.setUserId(userId);
        job.setStatus(BacktestJobStatus.QUEUED);
        job.setProgress(0);
        job.setProcessedBars(0);
        job.setTotalBars(0);
        job.setMetadataSchemaVersion(meta.schemaVersion());
        job.setStrategyDefinitionVersion(def.getVersion());
        job.setCorrelationId(CorrelationIdHolder.get());
        try {
            job.setRequestJson(objectMapper.writeValueAsString(resolved));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize execution request", e);
        }
        job = backtestJobRepository.save(job);
        UUID jobId = job.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    backtestReplayJobRunner.run(jobId);
                }
            });
        } else {
            backtestReplayJobRunner.run(jobId);
        }
        return jobId;
    }

    @Transactional(readOnly = true)
    public BacktestJobStatusDto statusForUser(UUID jobId, UUID userId) {
        BacktestJob job = backtestJobRepository
                .findByIdAndUserIdAndDeletedFalse(jobId, userId)
                .orElseThrow(() -> new NotFoundException("Backtest job not found"));
        return new BacktestJobStatusDto(
                job.getId(),
                job.getStatus(),
                job.getProgress(),
                job.getTotalBars(),
                job.getProcessedBars(),
                job.getRunId(),
                job.getMessage(),
                job.isCancelled(),
                job.getMetadataSchemaVersion(),
                job.getStrategyDefinitionVersion(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                computeEtaSeconds(job.getStartedAt(), job.getProcessedBars(), job.getTotalBars())
        );
    }

    @Transactional
    public void cancelJob(UUID jobId, UUID userId) {
        BacktestJob job = backtestJobRepository
                .findByIdAndUserIdAndDeletedFalse(jobId, userId)
                .orElseThrow(() -> new NotFoundException("Backtest job not found"));
        if (job.getStatus() == BacktestJobStatus.COMPLETED) {
            throw new BadRequestException("Job already completed");
        }
        if (job.getStatus() == BacktestJobStatus.CANCELLED) {
            return;
        }
        job.setCancelled(true);
        if (job.getStatus() == BacktestJobStatus.QUEUED) {
            job.setStatus(BacktestJobStatus.CANCELLED);
            job.setMessage("Cancelled before start");
        } else {
            job.setMessage("Cancellation requested — worker will stop cooperatively");
        }
        backtestJobRepository.save(job);
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
