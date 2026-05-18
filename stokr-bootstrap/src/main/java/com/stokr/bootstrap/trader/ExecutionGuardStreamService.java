package com.stokr.bootstrap.trader;

import com.stokr.execution.guard.ExecutionGuardRealtimeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ExecutionGuardStreamService {

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByUser.computeIfAbsent(userId, __ -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(__ -> remove(userId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("ts", Instant.now().toString())));
        } catch (IOException ignored) {
            remove(userId, emitter);
        }
        return emitter;
    }

    @EventListener
    public void onGuardEvent(ExecutionGuardRealtimeEvent event) {
        UUID userId = event.userId();
        if (userId == null) return;
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(event.eventType()).data(Map.of(
                        "emittedAt", event.emittedAt().toString(),
                        "severity", event.severity(),
                        "outcome", event.outcome(),
                        "symbol", event.symbol(),
                        "strategyKey", event.strategyKey(),
                        "payload", event.payload()
                )));
            } catch (Exception ex) {
                remove(userId, emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        for (Map.Entry<UUID, CopyOnWriteArrayList<SseEmitter>> e : emittersByUser.entrySet()) {
            for (SseEmitter emitter : new ArrayList<>(e.getValue())) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("ts", Instant.now().toString())));
                } catch (Exception ex) {
                    remove(e.getKey(), emitter);
                }
            }
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emittersByUser.get(userId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emittersByUser.remove(userId);
    }
}

