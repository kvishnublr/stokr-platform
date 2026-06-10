package com.stokr.bootstrap.signal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Tracks signal execution events as they move through the pipeline.
 *
 * This service will be integrated in Phase 2 (Week 1-2).
 * Currently, SignalLifecyclePanel shows mock data with DEMO MODE warning.
 *
 * Events tracked:
 * 1. SIGNAL_GENERATED - Signal created in Strategy Service
 * 2. SIGNAL_QUEUED - Signal published to RabbitMQ
 * 3. SIGNAL_RECEIVED - Execution Service consumed signal
 * 4. RISK_VALIDATION_STARTED - Risk check initiated
 * 5. RISK_VALIDATION_PASSED/FAILED - Risk validation result
 * 6. BROKER_SUBMISSION_STARTED - Order submission initiated
 * 7. ORDER_FILLED - Broker confirmed order fill
 * 8. POSITION_CREATED - Core trading created position record
 * 9. ERROR - Any step failed
 *
 * This service will populate a new database table: signal_execution_events
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalExecutionEventTracker {

    // TODO: Inject SignalExecutionEventRepository when created in Phase 2
    // private final SignalExecutionEventRepository eventRepository;

    /**
     * Log an execution event for a signal
     *
     * Should be called at each step of the signal execution pipeline:
     * - In StrategyExecutionOrchestrator when signal is generated
     * - In SignalPublisherService when signal is published to RabbitMQ
     * - In SignalConsumer when signal is received
     * - In RiskValidationClient before/after risk check
     * - In BrokerIntegrationService before/after order submission
     * - In PositionManagementService when position is created
     * - In exception handlers when errors occur
     */
    @Transactional
    public void logEvent(String signalId, String eventType, String serviceName) {
        logEvent(signalId, eventType, serviceName, 0, null);
    }

    /**
     * Log an execution event with duration
     */
    @Transactional
    public void logEvent(String signalId, String eventType, String serviceName, long durationMs) {
        logEvent(signalId, eventType, serviceName, durationMs, null);
    }

    /**
     * Log an execution event with error details
     */
    @Transactional
    public void logEvent(String signalId, String eventType, String serviceName, String errorMessage) {
        logEvent(signalId, eventType, serviceName, 0, errorMessage);
    }

    /**
     * Log complete execution event
     */
    @Transactional
    public void logEvent(String signalId, String eventType, String serviceName, long durationMs, String errorMessage) {
        try {
            SignalExecutionEvent event = SignalExecutionEvent.builder()
                    .signalId(signalId)
                    .eventType(eventType)
                    .serviceName(serviceName)
                    .durationMs(durationMs)
                    .errorMessage(errorMessage)
                    .timestamp(Instant.now())
                    .build();

            // TODO: Uncomment when repository is created
            // eventRepository.save(event);

            log.debug("Signal {} event logged: {} by {} ({}ms)",
                    signalId, eventType, serviceName, durationMs);

            if (errorMessage != null) {
                log.warn("Signal {} error in {}: {}", signalId, serviceName, errorMessage);
            }

        } catch (Exception e) {
            log.error("Failed to log signal execution event: {}", e.getMessage(), e);
            // Don't throw - tracking failure shouldn't block execution
        }
    }

    /**
     * Get execution timeline for a signal
     * Returns all events in chronological order
     */
    public List<SignalExecutionEvent> getExecutionTimeline(String signalId) {
        try {
            // TODO: Replace with repository query when created
            // return eventRepository.findBySignalIdOrderByTimestampAsc(signalId);

            return new ArrayList<>();  // Return empty for now

        } catch (Exception e) {
            log.error("Failed to get execution timeline for signal {}: {}", signalId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get total latency for a signal (from first to last event)
     */
    public long getTotalLatency(String signalId) {
        List<SignalExecutionEvent> events = getExecutionTimeline(signalId);
        if (events.isEmpty()) {
            return 0;
        }

        long firstTime = events.get(0).getTimestamp().toEpochMilli();
        long lastTime = events.get(events.size() - 1).getTimestamp().toEpochMilli();
        return lastTime - firstTime;
    }

    /**
     * Get latency for a specific step
     */
    public long getStepLatency(String signalId, String eventType) {
        List<SignalExecutionEvent> events = getExecutionTimeline(signalId);
        for (SignalExecutionEvent event : events) {
            if (eventType.equals(event.getEventType())) {
                return event.getDurationMs();
            }
        }
        return 0;
    }

    /**
     * Entity for signal_execution_events table
     * TODO: Create proper JPA entity in Phase 2
     */
    @lombok.Data
    @lombok.Builder
    public static class SignalExecutionEvent {
        private Long id;
        private String signalId;
        private String eventType;  // GENERATED, QUEUED, RECEIVED, RISK_CHECK, FILLED, etc
        private String serviceName;  // strategy-service, execution-service, core-trading, broker
        private long durationMs;  // How long this step took
        private String errorMessage;  // If event is an error
        private Instant timestamp;  // When event occurred
    }
}
