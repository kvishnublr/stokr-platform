package com.stokr.execution.service;

import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.execution.simulation.ExecutionSimulator;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final RabbitTemplate rabbitTemplate;
    private final ExecutionSimulator executionSimulator;

    public void dispatch(ExecutionDispatchMessage message, boolean synchronous) {
        if (synchronous) {
            executionSimulator.process(message);
            return;
        }
        rabbitTemplate.convertAndSend(PipelineQueues.EXECUTION, message);
    }
}
