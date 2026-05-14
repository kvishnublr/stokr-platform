package com.stokr.websocket.bus;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class LiveUpdatePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publishBacktestJob(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/backtest.jobs." + userId, payload);
    }

    public void publishOrderUpdate(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/orders." + userId, payload);
    }

    public void publishPositionUpdate(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/positions." + userId, payload);
    }

    public void publishPnlUpdate(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/pnl." + userId, payload);
    }

    public void publishMarket(String symbol, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/market." + symbol, payload);
    }

    public void publishSignal(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/signals." + userId, payload);
    }

    public void publishStrategyRuntime(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/strategies." + userId, payload);
    }

    /** Fan-out topic for privileged subscribers (admin consoles). */
    public void publishAdminBroadcast(Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/admin.broadcast", payload);
    }
}
