package com.stokr.execution.messaging;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.execution.pipeline.OrderIntentProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "stokr.rabbit", name = "listeners-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OmsOrderIntentListener {

    private final OrderIntentProcessor orderIntentProcessor;
    private final SignalDistributionTelemetryService signalDistributionTelemetryService;
    private final ApplicationEventPublisher eventPublisher;

    @RabbitListener(queues = PipelineQueues.OMS_ORDER)
    public void onMessage(SignalPersistedMessage message) {
        signalDistributionTelemetryService.recordOmsIntentReceived(message);
        eventPublisher.publishEvent(new OperationalRealtimeEvent("oms_intent", Map.of(
                "signalId", message.signalId().toString(),
                "userId", message.userId() != null ? message.userId().toString() : ""
        )));
        log.info("oms.intent.received signalId={}", message.signalId());
        try {
            orderIntentProcessor.processSignalIntent(message, false);
        } catch (Exception ex) {
            // Never requeue on processing failure — this causes the "stuck-1" OMS degraded state.
            // Throw AmqpRejectAndDontRequeueException so RabbitMQ routes the message to OMS_ORDER_DLQ
            // instead of blocking the entire queue with an infinite retry loop.
            log.error("oms.intent.processing_failed signalId={} — routing to DLQ: {}",
                    message.signalId(), ex.getMessage(), ex);
            throw new AmqpRejectAndDontRequeueException(
                    "OMS order processing failed for signalId=" + message.signalId(), ex);
        }
    }
}
