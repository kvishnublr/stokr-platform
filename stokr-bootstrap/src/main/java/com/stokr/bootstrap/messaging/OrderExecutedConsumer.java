package com.stokr.bootstrap.messaging;

import com.stokr.common.messaging.RabbitMQConfig;
import com.stokr.common.messaging.events.OrderExecutedEvent;
import com.stokr.bootstrap.positioning.PositionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes OrderExecutedEvent from trading.orders queue published by Execution Service.
 * Updates position state in core trading system asynchronously.
 *
 * Queue: trading.orders (durable)
 * Exchange: trading.events
 * Routing Key: order.executed
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExecutedConsumer {

    private final PositionManagementService positionManagementService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDERS)
    public void consumeOrderExecuted(OrderExecutedEvent event) {
        try {
            log.info("📦 ORDER EXECUTED EVENT: {} | Order: {} | Symbol: {} | Qty: {}",
                    event.getSignalId(),
                    event.getOrderId(),
                    event.getSymbol(),
                    event.getFilledQuantity());

            // Update position state asynchronously
            positionManagementService.updatePositionFromOrderExecution(event);

            log.info("✅ POSITION UPDATED: {} | Order: {}", event.getSignalId(), event.getOrderId());

        } catch (Exception e) {
            log.error("❌ ERROR updating position from order execution: {} | Error: {}",
                    event.getOrderId(), e.getMessage(), e);
            // Message will be requeued by RabbitMQ (handled by delivery acknowledgment)
            throw new RuntimeException("Order execution event processing failed", e);
        }
    }
}
