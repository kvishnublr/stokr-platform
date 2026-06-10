package com.stokr.bootstrap.messaging;

import com.stokr.common.messaging.RabbitMQConfig;
import com.stokr.common.messaging.events.ExitSignalEvent;
import com.stokr.bootstrap.positioning.ExitExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes ExitSignalEvent from trading.exits queue.
 * Handles automated exit execution (stop loss, take profit, trailing stops).
 *
 * Queue: trading.exits (durable, 1-minute TTL - exits are time-sensitive)
 * Exchange: trading.events
 * Routing Key: exit.signal
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExitSignalConsumer {

    private final ExitExecutionService exitExecutionService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_EXITS)
    public void consumeExitSignal(ExitSignalEvent event) {
        try {
            log.info("🚪 EXIT SIGNAL: {} | Order: {} | Reason: {} | P&L: {}",
                    event.getExitSignalId(),
                    event.getOrderId(),
                    event.getReason(),
                    event.getPnlPercentage() != null ? String.format("%.2f%%", event.getPnlPercentage()) : "N/A");

            // Execute exit order immediately
            exitExecutionService.processExitSignal(event);

            log.info("✅ EXIT PROCESSED: {} | Reason: {}", event.getExitSignalId(), event.getReason());

        } catch (Exception e) {
            log.error("❌ ERROR processing exit signal: {} | Error: {}",
                    event.getExitSignalId(), e.getMessage(), e);
            // Message will be requeued by RabbitMQ (handled by delivery acknowledgment)
            throw new RuntimeException("Exit signal processing failed", e);
        }
    }
}
