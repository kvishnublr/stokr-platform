package com.stokr.execution.messaging;

import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.execution.pipeline.OrderIntentProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OmsOrderIntentListener {

    private final OrderIntentProcessor orderIntentProcessor;

    @RabbitListener(queues = PipelineQueues.OMS_ORDER)
    public void onMessage(SignalPersistedMessage message) {
        log.info("oms.intent.received signalId={}", message.signalId());
        orderIntentProcessor.processSignalIntent(message, false);
    }
}
