package com.stokr.execution.messaging;

import com.stokr.common.events.ExecutionDispatchFailedEvent;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.execution.service.RetryHandler;
import com.stokr.execution.simulation.ExecutionSimulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "stokr.rabbit", name = "listeners-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ExecutionConsumer {

    private final ExecutionSimulator executionSimulator;
    private final RetryHandler retryHandler;
    private final ApplicationEventPublisher eventPublisher;

    @RabbitListener(queues = PipelineQueues.EXECUTION)
    public void onMessage(ExecutionDispatchMessage msg) {
        try {
            executionSimulator.process(msg);
        } catch (Exception ex) {
            log.error("execution.failed orderId={}", msg.orderId(), ex);
            String reason = ex.getClass().getSimpleName() + ": "
                    + (ex.getMessage() != null ? ex.getMessage() : "");
            if (reason.length() > 500) {
                reason = reason.substring(0, 500);
            }
            eventPublisher.publishEvent(new ExecutionDispatchFailedEvent(
                    msg.orderId(),
                    msg.userId(),
                    msg.signalId(),
                    reason
            ));
            retryHandler.retryOrDlq(msg, ex);
        }
    }
}
