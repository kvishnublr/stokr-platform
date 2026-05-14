package com.stokr.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.engine.BacktestReplayJobRunner;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.backtest.validation.StrategyExecutionRequestValidator;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestJobEnqueueServiceCancelTest {

    @Mock
    private BacktestJobRepository backtestJobRepository;

    @Mock
    private StrategyExecutionRequestValidator strategyExecutionRequestValidator;

    @Mock
    private StrategyDefinitionRepository strategyDefinitionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private BacktestReplayJobRunner backtestReplayJobRunner;

    @InjectMocks
    private BacktestJobEnqueueService service;

    @Test
    void cancelCompletedThrows() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        BacktestJob job = new BacktestJob();
        job.setStatus(BacktestJobStatus.COMPLETED);
        when(backtestJobRepository.findByIdAndUserIdAndDeletedFalse(jobId, userId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancelJob(jobId, userId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void cancelQueuedMarksCancelled() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        BacktestJob job = new BacktestJob();
        job.setId(jobId);
        job.setUserId(userId);
        job.setStatus(BacktestJobStatus.QUEUED);
        when(backtestJobRepository.findByIdAndUserIdAndDeletedFalse(jobId, userId)).thenReturn(Optional.of(job));

        service.cancelJob(jobId, userId);

        verify(backtestJobRepository).save(any(BacktestJob.class));
    }

    @Test
    void cancelUnknownThrowsNotFound() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(backtestJobRepository.findByIdAndUserIdAndDeletedFalse(jobId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelJob(jobId, userId)).isInstanceOf(NotFoundException.class);
    }
}
