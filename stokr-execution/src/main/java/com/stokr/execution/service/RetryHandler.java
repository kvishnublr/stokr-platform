package com.stokr.execution.service;

import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final RabbitTemplate rabbitTemplate;

    @Value("${stokr.execution.max-attempts:5}")
    private int maxAttempts;

    public void retryOrDlq(ExecutionDispatchMessage msg, Throwable error) {
        if (msg.attempt() + 1 >= maxAttempts) {
            rabbitTemplate.convertAndSend(PipelineQueues.EXECUTION_DLQ, msg);
            return;
        }
        ExecutionDispatchMessage next = new ExecutionDispatchMessage(
                msg.orderId(),
                msg.userId(),
                msg.signalId(),
                msg.brokerVendor(),
                msg.attempt() + 1,
                msg.backtestRunId(),
                msg.executionMode()
        );
        rabbitTemplate.convertAndSend(PipelineQueues.EXECUTION, next);
    }
}
