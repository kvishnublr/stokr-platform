package com.stokr.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.messaging.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for publishing domain events to RabbitMQ.
 * Handles serialization, correlation IDs, and error handling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePublisher {
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishSignalGenerated(SignalGeneratedEvent event) {
        try {
            // Set correlation ID if not already set
            if (event.getCorrelationId() == null) {
                event.setCorrelationId(CorrelationIdHolder.getCorrelationId());
            }
            if (event.getSignalId() == null) {
                event.setSignalId("sig-" + UUID.randomUUID());
            }

            log.info("Publishing signal: {} | {} {} | aiScore: {} | correlationId: {}",
                    event.getSignalId(), event.getSide(), event.getSymbol(),
                    event.getAiScore(), event.getCorrelationId());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_TRADING,
                    RabbitMQConfig.ROUTING_KEY_SIGNAL,
                    event
            );
        } catch (Exception e) {
            log.error("Failed to publish signal: {} | {}", event.getSignalId(), e.getMessage(), e);
            throw new RuntimeException("Failed to publish signal", e);
        }
    }

    public void publishOrderExecuted(OrderExecutedEvent event) {
        try {
            if (event.getCorrelationId() == null) {
                event.setCorrelationId(CorrelationIdHolder.getCorrelationId());
            }
            if (event.getOrderId() == null) {
                event.setOrderId("ord-" + UUID.randomUUID());
            }

            log.info("Publishing order execution: {} | {} {} | status: {} | correlationId: {}",
                    event.getOrderId(), event.getSide(), event.getSymbol(),
                    event.getStatus(), event.getCorrelationId());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_TRADING,
                    RabbitMQConfig.ROUTING_KEY_ORDER,
                    event
            );
        } catch (Exception e) {
            log.error("Failed to publish order execution: {} | {}", event.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("Failed to publish order execution", e);
        }
    }

    public void publishExitSignal(ExitSignalEvent event) {
        try {
            if (event.getCorrelationId() == null) {
                event.setCorrelationId(CorrelationIdHolder.getCorrelationId());
            }
            if (event.getExitSignalId() == null) {
                event.setExitSignalId("exit-" + UUID.randomUUID());
            }

            log.info("Publishing exit signal: {} | {} {} | reason: {} | correlationId: {}",
                    event.getExitSignalId(), event.getSide(), event.getSymbol(),
                    event.getReason(), event.getCorrelationId());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_TRADING,
                    RabbitMQConfig.ROUTING_KEY_EXIT,
                    event
            );
        } catch (Exception e) {
            log.error("Failed to publish exit signal: {} | {}", event.getExitSignalId(), e.getMessage(), e);
            throw new RuntimeException("Failed to publish exit signal", e);
        }
    }

    public void publishAudit(AuditEvent event) {
        try {
            if (event.getCorrelationId() == null) {
                event.setCorrelationId(CorrelationIdHolder.getCorrelationId());
            }
            if (event.getAuditId() == null) {
                event.setAuditId("audit-" + UUID.randomUUID());
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(java.time.Instant.now());
            }

            log.debug("Publishing audit event: {} | action: {} | entity: {} | correlationId: {}",
                    event.getAuditId(), event.getAction(), event.getEntityType(),
                    event.getCorrelationId());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_TRADING,
                    RabbitMQConfig.ROUTING_KEY_AUDIT,
                    event
            );
        } catch (Exception e) {
            log.error("Failed to publish audit event: {} | {}", event.getAuditId(), e.getMessage(), e);
            // Don't fail the request if audit fails, just log
        }
    }
}
