package com.stokr.websocket.bus;

import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges domain events from the JVM to STOMP topics for browser clients.
 */
@Component
public class RealtimeBridgeListener {

    private final LiveUpdatePublisher publisher;

    public RealtimeBridgeListener(LiveUpdatePublisher publisher) {
        this.publisher = publisher;
    }

    @Async
    @EventListener
    public void onBacktestJob(RealtimeBridgeEvents.BacktestJobProgress e) {
        String uid = e.userId().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "BACKTEST_JOB");
        payload.put("jobId", e.jobId().toString());
        payload.put("runId", e.runId() != null ? e.runId().toString() : null);
        payload.put("processedBars", e.processedBars());
        payload.put("totalBars", e.totalBars());
        payload.put("progressPct", e.progressPct());
        payload.put("etaSecondsRemaining", e.etaSecondsRemaining());
        payload.put("status", e.status());
        publisher.publishBacktestJob(uid, payload);
    }

    @Async
    @EventListener
    public void onOrder(RealtimeBridgeEvents.OrderUpdate e) {
        String uid = e.userId().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ORDER_UPDATE");
        payload.put("orderId", e.orderId().toString());
        payload.put("symbol", e.symbol());
        payload.put("state", e.orderState());
        payload.put("at", e.at().toString());
        publisher.publishOrderUpdate(uid, payload);
    }

    @Async
    @EventListener
    public void onPnl(RealtimeBridgeEvents.PnlUpdated e) {
        String uid = e.userId().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "PNL_UPDATE");
        payload.put("at", e.at().toString());
        publisher.publishPnlUpdate(uid, payload);
    }

    @Async
    @EventListener
    public void onStrategy(RealtimeBridgeEvents.StrategyRuntime e) {
        String uid = e.userId().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "STRATEGY_RUNTIME");
        payload.put("instanceId", e.instanceId().toString());
        payload.put("runtimeState", e.runtimeState());
        payload.put("at", e.at().toString());
        publisher.publishStrategyRuntime(uid, payload);
    }
}
