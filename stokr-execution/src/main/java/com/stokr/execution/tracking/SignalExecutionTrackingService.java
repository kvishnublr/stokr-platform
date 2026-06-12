package com.stokr.execution.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalExecutionTrackingService {

    private final SignalExecutionTrackRepository trackRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SignalExecutionTrack initializeTrack(StrategySignalEntity signal, UUID userId) {
        SignalExecutionTrack track = new SignalExecutionTrack();
        track.setSignalId(signal.getId());
        track.setUserId(userId);
        track.setStrategyKey(signal.getStrategyName() != null ? signal.getStrategyName() : "UNKNOWN");
        track.setSymbol(signal.getSymbol());
        track.setSignalType(signal.getSignalType() != null ? signal.getSignalType().name() : "UNKNOWN");
        track.setStatus(SignalExecutionTrack.SignalExecutionStatus.GENERATED);
        track.setCurrentStep("Signal generated and dispatched");
        track.setRetryCount(0);
        track.setCreatedAt(Instant.now());
        track.setUpdatedAt(Instant.now());

        return trackRepository.save(track);
    }

    @Transactional
    public void recordStep(UUID signalId, UUID userId, String stepName, SignalExecutionTrack.SignalExecutionStatus status) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseGet(() -> new SignalExecutionTrack());
        track.setSignalId(signalId);
        track.setUserId(userId);
        track.setStatus(status);
        track.setCurrentStep(stepName);
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);
        log.info("signal.execution.step signalId={} userId={} step={} status={}", signalId, userId, stepName, status);
    }

    @Transactional
    public void recordOrderCreated(UUID signalId, UUID userId, UUID orderId, String executionMode,
                                   String side, BigDecimal quantity) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseGet(() -> new SignalExecutionTrack());
        track.setSignalId(signalId);
        track.setUserId(userId);
        track.setOrderId(orderId);
        track.setStatus(SignalExecutionTrack.SignalExecutionStatus.ORDER_CREATED);
        track.setCurrentStep("Order created in OMS");
        track.setExecutionMode(executionMode);
        track.setSide(side);
        track.setQuantity(quantity);
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);
        log.info("signal.execution.order_created signalId={} orderId={} mode={}", signalId, orderId, executionMode);
    }

    @Transactional
    public void recordBrokerSubmission(UUID signalId, UUID userId, String brokerOrderId, String brokerVendor) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseGet(() -> new SignalExecutionTrack());
        track.setSignalId(signalId);
        track.setUserId(userId);
        track.setStatus(SignalExecutionTrack.SignalExecutionStatus.SUBMITTED);
        track.setCurrentStep("Submitted to " + brokerVendor);
        track.setBrokerOrderId(brokerOrderId);
        track.setBrokerVendor(brokerVendor);
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);
        log.info("signal.execution.submitted signalId={} brokerOrderId={} vendor={}", signalId, brokerOrderId, brokerVendor);
    }

    @Transactional
    public void recordBrokerAccepted(UUID signalId, UUID userId, String brokerOrderId, BigDecimal entryPrice) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseThrow(() -> new IllegalStateException("Track not found: " + signalId));
        track.setStatus(SignalExecutionTrack.SignalExecutionStatus.ACCEPTED);
        track.setCurrentStep("Broker accepted order");
        track.setBrokerOrderId(brokerOrderId);
        track.setEntryPrice(entryPrice);
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);
        log.info("signal.execution.accepted signalId={} price={}", signalId, entryPrice);
    }

    @Transactional
    public void recordFilled(UUID signalId, UUID userId) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseThrow(() -> new IllegalStateException("Track not found: " + signalId));
        Instant now = Instant.now();
        track.setStatus(SignalExecutionTrack.SignalExecutionStatus.FILLED);
        track.setCurrentStep("Trade execution completed");
        track.setFilledAt(now);
        if (track.getCreatedAt() != null) {
            track.setExecutionTimeMs(ChronoUnit.MILLIS.between(track.getCreatedAt(), now));
        }
        track.setUpdatedAt(now);
        trackRepository.save(track);
        log.info("signal.execution.filled signalId={} executionTimeMs={}", signalId, track.getExecutionTimeMs());
    }

    @Transactional
    public void recordFailure(UUID signalId, UUID userId, SignalExecutionTrack.SignalExecutionStatus failureStatus,
                             String failureReason, String errorDetail) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseGet(() -> new SignalExecutionTrack());
        track.setSignalId(signalId);
        track.setUserId(userId);
        track.setStatus(failureStatus);
        track.setFailureReason(failureReason);
        track.setLastError(errorDetail);
        track.setCurrentStep("Failed: " + failureReason);
        track.setRetryCount((track.getRetryCount() != null ? track.getRetryCount() : 0));
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);
        log.warn("signal.execution.failed signalId={} reason={} error={}", signalId, failureReason, errorDetail);
    }

    @Transactional
    public void recordRetry(UUID signalId, UUID userId) {
        SignalExecutionTrack track = trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElseThrow(() -> new IllegalStateException("Track not found: " + signalId));
        track.setStatus(SignalExecutionTrack.SignalExecutionStatus.RETRYING);
        track.setRetryCount((track.getRetryCount() != null ? track.getRetryCount() : 0) + 1);
        track.setLastRetryAt(Instant.now());
        track.setCurrentStep("Retrying execution (attempt " + track.getRetryCount() + ")");
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);
        log.info("signal.execution.retry signalId={} attempt={}", signalId, track.getRetryCount());
    }

    public SignalExecutionTrack getTrack(UUID signalId, UUID userId) {
        return trackRepository.findBySignalIdAndUserId(signalId, userId)
                .orElse(null);
    }

    public List<SignalExecutionTrack> getPendingByStrategy(String strategyKey) {
        return trackRepository.findPendingByStrategy(strategyKey);
    }

    public List<SignalExecutionTrack> getRetryableFailures(int maxRetries, int retryAfterMinutes) {
        Instant retryBefore = Instant.now().minus(retryAfterMinutes, java.time.temporal.ChronoUnit.MINUTES);
        return trackRepository.findRetryableFailures(maxRetries, retryBefore, PageRequest.of(0, 100));
    }
}
