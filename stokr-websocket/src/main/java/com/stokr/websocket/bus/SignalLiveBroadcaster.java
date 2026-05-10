package com.stokr.websocket.bus;

import com.stokr.common.events.SignalPublishedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SignalLiveBroadcaster {

    private final LiveUpdatePublisher liveUpdatePublisher;

    @EventListener
    public void onSignal(SignalPublishedEvent event) {
        if (event.userId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SIGNAL");
        payload.put("signalId", event.signalId().toString());
        payload.put("symbol", event.symbol());
        payload.put("strategyKey", event.strategyKey());
        liveUpdatePublisher.publishSignal(event.userId().toString(), payload);
    }
}
