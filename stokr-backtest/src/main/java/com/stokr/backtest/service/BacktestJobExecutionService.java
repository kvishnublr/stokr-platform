package com.stokr.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.engine.MeanReversionReplayService;
import com.stokr.backtest.engine.ReplayCancelledException;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.backtest.web.dto.ExecutionRequestDto;
import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestJobExecutionService {

    private final BacktestJobRepository jobRepository;
    private final BacktestJobStatusWriter statusWriter;
    private final MeanReversionReplayService meanReversionReplayService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void execute(UUID jobId) {
        BacktestJob initial = jobRepository.findById(jobId).orElse(null);
        if (initial == null || initial.isDeleted()) {
            return;
        }
        if (initial.getStatus() == BacktestJobStatus.CANCELLED) {
            return;
        }
        if (initial.isCancelled() && initial.getStatus() == BacktestJobStatus.QUEUED) {
            statusWriter.markQueuedCancelled(jobId, "Cancelled before worker started");
            return;
        }

        statusWriter.markRunning(jobId);
        if (statusWriter.isJobCancelled(jobId)) {
            statusWriter.markCancelledAfterReplay(jobId, null, "Cancelled immediately after start");
            return;
        }

        try {
            BacktestJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.isDeleted()) {
                return;
            }
            if (job.isCancelled()) {
                statusWriter.markCancelledAfterReplay(jobId, job.getRunId(), "Cancelled before replay");
                return;
            }
            ExecutionRequestDto request = objectMapper.readValue(job.getRequestJson(), ExecutionRequestDto.class);
            var tracker = new BacktestJobReplayProgressTracker(jobId, job.getUserId(), statusWriter, eventPublisher);
            BacktestReplayOutcome outcome = meanReversionReplayService.runReplay(
                    request,
                    job.getUserId(),
                    job.getMetadataSchemaVersion() != null ? job.getMetadataSchemaVersion() : 0,
                    job.getStrategyDefinitionVersion() != null ? job.getStrategyDefinitionVersion() : 0L,
                    tracker
            );
            statusWriter.markCompleted(jobId, outcome);
            BacktestJob done = jobRepository.findById(jobId).orElseThrow();
            eventPublisher.publishEvent(new RealtimeBridgeEvents.BacktestJobProgress(
                    done.getUserId(),
                    jobId,
                    outcome.runId(),
                    done.getProcessedBars(),
                    Math.max(done.getTotalBars(), 1),
                    100,
                    0L,
                    "COMPLETED"
            ));
        } catch (ReplayCancelledException e) {
            BacktestJob j = jobRepository.findById(jobId).orElse(null);
            statusWriter.markCancelledAfterReplay(jobId, j != null ? j.getRunId() : null, "Cancelled by user");
            if (j != null) {
                BacktestJob fresh = jobRepository.findById(jobId).orElse(j);
                eventPublisher.publishEvent(new RealtimeBridgeEvents.BacktestJobProgress(
                        fresh.getUserId(),
                        jobId,
                        fresh.getRunId(),
                        fresh.getProcessedBars(),
                        Math.max(fresh.getTotalBars(), 1),
                        fresh.getProgress(),
                        null,
                        "CANCELLED"
                ));
            }
        } catch (BadRequestException e) {
            log.warn("backtest.job.bad_request jobId={} msg={}", jobId, e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            statusWriter.markFailed(jobId, msg, ReplayDiagnosisClassifier.fromFailureMessage(msg));
        } catch (Exception e) {
            log.error("backtest.job.failed jobId={}", jobId, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            statusWriter.markFailed(jobId, msg, ReplayDiagnosisClassifier.fromFailureMessage(msg));
        }
    }
}
