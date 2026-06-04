package com.stokr.execution.service;

import com.stokr.common.pipeline.OmsIntentDispatcher;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.execution.tracking.SignalExecutionTrack;
import com.stokr.execution.tracking.SignalExecutionTrackingService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalExecutionFallbackService {

    private final SignalExecutionTrackingService trackingService;
    private final OmsIntentDispatcher omsIntentDispatcher;
    private final StrategySignalRepository signalRepository;

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_WAIT_MINUTES = 2;
    private static final String FALLBACK_CHAIN = "LIVE -> BOTH -> PAPER";

    /**
     * Automatically retry failed signal executions with fallback execution modes.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelayString = "${stokr.execution.fallback.retry-interval-ms:300000}")
    @Transactional
    public void retryFailedSignalsWithFallback() {
        List<SignalExecutionTrack> retryable = trackingService.getRetryableFailures(MAX_RETRIES, RETRY_WAIT_MINUTES);

        for (SignalExecutionTrack track : retryable) {
            try {
                retryWithFallback(track);
            } catch (Exception ex) {
                log.error("fallback_retry.failed signalId={} userId={} error={}",
                        track.getSignalId(), track.getUserId(), ex.toString());
            }
        }

        if (!retryable.isEmpty()) {
            log.info("signal.execution.fallback_retry_batch count={} fallback_chain={}",
                    retryable.size(), FALLBACK_CHAIN);
        }
    }

    /**
     * Retry a failed signal with appropriate fallback execution mode.
     */
    @Transactional
    public void retryWithFallback(SignalExecutionTrack track) {
        String currentMode = track.getExecutionMode();
        String nextMode = resolveFallbackMode(currentMode, track.getRetryCount());

        if (nextMode == null) {
            log.warn("signal.execution.no_fallback signalId={} maxRetries={} exhausted",
                    track.getSignalId(), MAX_RETRIES);
            return;
        }

        if (nextMode.equals(currentMode)) {
            log.info("signal.execution.retry_same_mode signalId={} mode={} attempt={}",
                    track.getSignalId(), currentMode, track.getRetryCount() + 1);
        } else {
            log.info("signal.execution.fallback_mode_switch signalId={} from={} to={} attempt={}",
                    track.getSignalId(), currentMode, nextMode, track.getRetryCount() + 1);
        }

        trackingService.recordRetry(track.getSignalId(), track.getUserId());

        SignalPersistedMessage retryMsg = new SignalPersistedMessage(
                track.getSignalId(),
                track.getUserId(),
                UUID.randomUUID().toString(),
                null,
                nextMode
        );

        try {
            omsIntentDispatcher.dispatch(retryMsg, true);
            log.info("signal.execution.fallback_dispatched signalId={} userId={} mode={} attempt={}",
                    track.getSignalId(), track.getUserId(), nextMode, track.getRetryCount());
        } catch (Exception ex) {
            log.error("signal.execution.fallback_dispatch_failed signalId={} mode={} error={}",
                    track.getSignalId(), nextMode, ex.toString());
            throw ex;
        }
    }

    /**
     * Determine next execution mode based on current mode and retry count.
     * Fallback chain: LIVE → BOTH → PAPER
     */
    private String resolveFallbackMode(String currentMode, Integer retryCount) {
        if (currentMode == null || currentMode.isBlank()) {
            return "PAPER";
        }

        int attempt = (retryCount != null ? retryCount : 0) + 1;
        if (attempt > MAX_RETRIES) {
            return null;
        }

        String mode = currentMode.toUpperCase();

        // First attempt: try same mode (real issue might be transient)
        if (attempt == 1) {
            return mode;
        }

        // Second attempt: downgrade LIVE to BOTH
        if ("LIVE".equals(mode) && attempt == 2) {
            return "BOTH";
        }

        // Third attempt: downgrade to PAPER
        if (attempt == 3) {
            return "PAPER";
        }

        return "PAPER";
    }

    /**
     * Get execution summary for a signal.
     */
    public SignalExecutionSummary getExecutionSummary(UUID signalId, UUID userId) {
        SignalExecutionTrack track = trackingService.getTrack(signalId, userId);
        if (track == null) {
            return null;
        }

        return new SignalExecutionSummary(
                signalId,
                track.getStrategyKey(),
                track.getSymbol(),
                track.getSignalType(),
                track.getStatus().name(),
                track.getExecutionMode(),
                track.getBrokerVendor(),
                track.getOrderId(),
                track.getBrokerOrderId(),
                track.getSide(),
                track.getQuantity(),
                track.getEntryPrice(),
                track.getCurrentStep(),
                track.getFailureReason(),
                track.getLastError(),
                track.getRetryCount(),
                track.getExecutionTimeMs(),
                track.getCreatedAt(),
                track.getFilledAt(),
                track.getUpdatedAt()
        );
    }

    public record SignalExecutionSummary(
            UUID signalId,
            String strategyKey,
            String symbol,
            String signalType,
            String status,
            String executionMode,
            String brokerVendor,
            UUID orderId,
            String brokerOrderId,
            String side,
            java.math.BigDecimal quantity,
            java.math.BigDecimal entryPrice,
            String currentStep,
            String failureReason,
            String lastError,
            Integer retryCount,
            Long executionTimeMs,
            Instant createdAt,
            Instant filledAt,
            Instant updatedAt
    ) {
    }
}
