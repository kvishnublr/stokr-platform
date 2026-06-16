package com.stokr.execution.pipeline;

import com.stokr.common.pipeline.OmsIntentDispatcher;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultOmsIntentDispatcher implements OmsIntentDispatcher {

    private final OrderIntentProcessor orderIntentProcessor;
    private final RabbitTemplate rabbitTemplate;

    @Value("${stokr.execution.sync-oms-dispatch:true}")
    private boolean syncOmsDispatch;

    @Override
    public void dispatch(SignalPersistedMessage message, boolean synchronous) {
        if (message == null) {
            return;
        }
        boolean inline = synchronous || syncOmsDispatch;
        if (inline) {
            long start = System.nanoTime();
            orderIntentProcessor.processSignalIntent(message, true);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            log.info("oms.intent.sync_complete signalId={} latencyMs={}", message.signalId(), ms);
            return;
        }
        rabbitTemplate.convertAndSend(PipelineQueues.OMS_ORDER, message);
    }
}
