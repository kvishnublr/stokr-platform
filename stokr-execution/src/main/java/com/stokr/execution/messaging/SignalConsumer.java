package com.stokr.execution.messaging;

import com.stokr.common.messaging.RabbitMQConfig;
import com.stokr.common.messaging.events.SignalGeneratedEvent;
import com.stokr.execution.service.ExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ consumer for trading signals.
 * Listens to trading.signals queue published by Strategy Service.
 * Processes signals and initiates order execution.
 *
 * Queue: trading.signals (durable, 5-minute TTL)
 * Exchange: trading.events
 * Routing Key: signal.generated
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignalConsumer {

    private final ExecutionService executionService;

    /**
     * Consume signal from RabbitMQ and execute order.
     * Runs asynchronously per message.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_SIGNALS)
    public void consumeSignal(SignalGeneratedEvent signal) {
        try {
            log.info("📨 SIGNAL RECEIVED: {} | {} {} | aiScore: {} | executionMode: {}",
                    signal.getSignalId(),
                    signal.getSide(),
                    signal.getSymbol(),
                    signal.getAiScore(),
                    signal.getExecutionMode());

            // Process the signal and place order
            executionService.executeSignal(signal);

            log.info("✅ SIGNAL PROCESSED: {}", signal.getSignalId());

        } catch (Exception e) {
            log.error("❌ ERROR processing signal: {} | Error: {}",
                    signal.getSignalId(), e.getMessage(), e);
            // Message will be requeued by RabbitMQ (handled by delivery acknowledgment)
            throw new RuntimeException("Signal processing failed", e);
        }
    }
}
