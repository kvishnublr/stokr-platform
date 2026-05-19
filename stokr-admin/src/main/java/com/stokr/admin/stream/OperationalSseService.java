package com.stokr.admin.stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.admin.dto.OperationsSnapshotDto;
import com.stokr.admin.service.AdminOperationalSnapshotService;
import com.stokr.common.events.ExecutionDispatchFailedEvent;
import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.SignalPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multiplexed admin operations SSE: periodic snapshot ticks plus immediate high-signal events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationalSseService {

    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;

    private final AdminOperationalSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("channel", "admin-ops", "ok", true), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            remove.run();
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 20_000)
    public void broadcastHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        broadcast("heartbeat", Map.of("ts", System.currentTimeMillis()));
    }

    @Scheduled(fixedDelayString = "${stokr.admin.ops.sse-tick-ms:3000}")
    public void broadcastSnapshotTick() {
        if (emitters.isEmpty()) {
            return;
        }
        OperationsSnapshotDto snap = snapshotService.snapshot();
        Map<String, Object> payload = objectMapper.convertValue(snap, new TypeReference<>() {});
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "tick");
        envelope.put("payload", payload);
        broadcast("tick", envelope);
    }

    @EventListener
    public void onSignalPublished(SignalPublishedEvent e) {
        if (emitters.isEmpty()) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "signal");
        envelope.put("payload", Map.of(
                "signalId", e.signalId().toString(),
                "userId", e.userId().toString(),
                "symbol", e.symbol() != null ? e.symbol() : "",
                "strategyKey", e.strategyKey() != null ? e.strategyKey() : ""
        ));
        broadcast("signal", envelope);
    }

    @EventListener
    public void onExecutionFailed(ExecutionDispatchFailedEvent e) {
        if (emitters.isEmpty()) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "execution_failure");
        envelope.put("payload", Map.of(
                "orderId", e.orderId().toString(),
                "userId", e.userId() != null ? e.userId().toString() : "",
                "signalId", e.signalId() != null ? e.signalId().toString() : "",
                "reason", e.reason() != null ? e.reason() : ""
        ));
        broadcast("execution_failure", envelope);
    }

    @EventListener
    public void onOperationalRealtime(OperationalRealtimeEvent e) {
        if (emitters.isEmpty()) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "ops_realtime");
        envelope.put("topic", e.topic());
        envelope.put("payload", e.payload() != null ? e.payload() : Map.of());
        broadcast("ops_realtime", envelope);
    }

    private void broadcast(String eventName, Object data) {
        List<SseEmitter> snapshot = List.copyOf(emitters);
        for (SseEmitter emitter : snapshot) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                emitters.remove(emitter);
                log.debug("ops.sse.client_dropped: {}", ex.getClass().getSimpleName());
            }
        }
    }
}
