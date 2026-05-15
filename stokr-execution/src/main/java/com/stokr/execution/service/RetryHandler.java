package com.stokr.execution.service;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.exception.NotFoundException;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.oms.trace.ExecutionTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectProvider<SignalDistributionTelemetryService> signalDistributionTelemetry;
    private final OrderLifecycleService orderLifecycleService;
    private final ExecutionTraceService executionTraceService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${stokr.execution.max-attempts:5}")
    private int maxAttempts;

    public void retryOrDlq(ExecutionDispatchMessage msg, Throwable error) {
        OmsOrder order = safeOrder(msg.orderId());
        if (msg.attempt() + 1 >= maxAttempts) {
            signalDistributionTelemetry.ifAvailable(SignalDistributionTelemetryService::recordExecutionDlq);
            if (order != null) {
                executionTraceService.trace(order, ExecutionEventType.EXECUTION_DLQ, Map.of(
                        "attempt", msg.attempt(),
                        "reason", error.getClass().getSimpleName()
                ));
            }
            eventPublisher.publishEvent(new OperationalRealtimeEvent("execution_dlq", Map.of(
                    "orderId", msg.orderId().toString(),
                    "attempt", msg.attempt()
            )));
            rabbitTemplate.convertAndSend(PipelineQueues.EXECUTION_DLQ, msg);
            return;
        }
        signalDistributionTelemetry.ifAvailable(SignalDistributionTelemetryService::recordExecutionRetry);
        if (order != null) {
            executionTraceService.trace(order, ExecutionEventType.EXECUTION_RETRY_SCHEDULED, Map.of(
                    "fromAttempt", msg.attempt(),
                    "toAttempt", msg.attempt() + 1
            ));
        }
        eventPublisher.publishEvent(new OperationalRealtimeEvent("execution_retry", Map.of(
                "orderId", msg.orderId().toString(),
                "nextAttempt", msg.attempt() + 1
        )));
        ExecutionDispatchMessage next = new ExecutionDispatchMessage(
                msg.orderId(),
                msg.userId(),
                msg.signalId(),
                msg.brokerVendor(),
                msg.attempt() + 1,
                msg.backtestRunId(),
                msg.executionMode(),
                msg.fillDeterminismKey(),
                msg.deterministicAnchor()
        );
        rabbitTemplate.convertAndSend(PipelineQueues.EXECUTION, next);
    }

    private OmsOrder safeOrder(java.util.UUID orderId) {
        try {
            return orderLifecycleService.getRequired(orderId);
        } catch (NotFoundException ex) {
            return null;
        }
    }
}
