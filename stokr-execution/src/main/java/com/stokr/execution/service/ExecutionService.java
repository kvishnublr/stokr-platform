package com.stokr.execution.service;

import com.stokr.common.messaging.MessagePublisher;
import com.stokr.common.messaging.events.SignalGeneratedEvent;
import com.stokr.common.messaging.events.OrderExecutedEvent;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.execution.simulation.ExecutionSimulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionService {

    private final RabbitTemplate rabbitTemplate;
    private final ExecutionSimulator executionSimulator;
    private final DeduplicationService deduplicationService;
    private final MessagePublisher messagePublisher;
    private final RiskValidationClient riskValidationClient;
    private final BrokerIntegrationService brokerIntegrationService;

    public void dispatch(ExecutionDispatchMessage message, boolean synchronous) {
        if (synchronous) {
            executionSimulator.process(message);
            return;
        }
        rabbitTemplate.convertAndSend(PipelineQueues.EXECUTION, message);
    }

    public void executeSignal(SignalGeneratedEvent signal) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("⏱️  SIGNAL EXECUTION START: {} | {}", signal.getSignalId(), signal.getSymbol());

            // Step 1: Check deduplication cache
            String cachedOrderId = deduplicationService.getProcessedOrderId(signal.getSignalId());
            if (cachedOrderId != null) {
                log.warn("⚠️  DUPLICATE DETECTED: Returning cached order {} for signal {}",
                        cachedOrderId, signal.getSignalId());
                return;
            }

            // Step 2: Risk validation (sync call)
            long riskCheckStart = System.currentTimeMillis();
            boolean riskCheckPassed = riskValidationClient.validateSignal(signal);
            long riskCheckDuration = System.currentTimeMillis() - riskCheckStart;

            if (!riskCheckPassed) {
                log.warn("❌ RISK CHECK FAILED: Signal {} rejected by risk validation", signal.getSignalId());
                publishOrderExecutedEvent(signal, null, riskCheckDuration, 0, false);
                return;
            }

            log.info("✅ RISK CHECK PASSED: {} | Duration: {}ms", signal.getSignalId(), riskCheckDuration);

            // Step 3: Broker order submission (sync call)
            long brokerSubmitStart = System.currentTimeMillis();
            String orderId = brokerIntegrationService.submitOrder(signal);
            long brokerSubmitDuration = System.currentTimeMillis() - brokerSubmitStart;

            if (orderId == null) {
                log.error("❌ BROKER SUBMISSION FAILED: Signal {}", signal.getSignalId());
                publishOrderExecutedEvent(signal, null, riskCheckDuration, brokerSubmitDuration, false);
                return;
            }

            log.info("📝 ORDER PLACED: {} | Order ID: {} | Duration: {}ms",
                    signal.getSignalId(), orderId, brokerSubmitDuration);

            // Step 4: Cache order for deduplication
            deduplicationService.cacheOrderForSignal(signal.getSignalId(), orderId);

            // Step 5: Publish order executed event
            long totalDuration = System.currentTimeMillis() - startTime;
            publishOrderExecutedEvent(signal, orderId, riskCheckDuration, brokerSubmitDuration, true);

            log.info("✅ SIGNAL EXECUTION COMPLETE: {} | Order: {} | Total: {}ms",
                    signal.getSignalId(), orderId, totalDuration);

        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("❌ SIGNAL EXECUTION ERROR: {} | Error: {} | Duration: {}ms",
                    signal.getSignalId(), e.getMessage(), totalDuration, e);
            throw new RuntimeException("Signal execution failed", e);
        }
    }

    private void publishOrderExecutedEvent(SignalGeneratedEvent signal, String orderId,
                                          long riskCheckDurationMs, long brokerSubmitDurationMs, boolean success) {
        OrderExecutedEvent event = OrderExecutedEvent.builder()
                .orderId(orderId)
                .signalId(signal.getSignalId())
                .symbol(signal.getSymbol())
                .side(signal.getSide())
                .requestedQuantity(signal.getQuantity())
                .filledQuantity(success ? signal.getQuantity() : 0)
                .filledPrice(java.math.BigDecimal.ZERO) // Will be updated when order fills
                .status(success ? "PENDING" : "REJECTED")
                .riskCheckPassed(success)
                .riskCheckDurationMs(riskCheckDurationMs)
                .brokerSubmitDurationMs(brokerSubmitDurationMs)
                .totalDurationMs(riskCheckDurationMs + brokerSubmitDurationMs)
                .executedAt(Instant.now())
                .build();

        messagePublisher.publishOrderExecuted(event);
    }
}
