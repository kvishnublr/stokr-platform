package com.stokr.admin.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Logback appender that holds a 500-entry ring buffer and pushes new entries
 * to connected SSE clients. Spring wires itself into the static singleton via
 * {@link #getInstance()} after context startup.
 */
public class SseLogAppender extends AppenderBase<ILoggingEvent> {

    private static final int RING_SIZE = 500;
    private static volatile SseLogAppender INSTANCE;

    private final Deque<Map<String, Object>> ring = new ArrayDeque<>(RING_SIZE);
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private ObjectMapper objectMapper;

    public static SseLogAppender getInstance() {
        return INSTANCE;
    }

    @Override
    public void start() {
        super.start();
        INSTANCE = this;
    }

    public void setObjectMapper(ObjectMapper om) {
        this.objectMapper = om;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, Object> entry = toMap(event);
        synchronized (ring) {
            if (ring.size() >= RING_SIZE) {
                ring.pollFirst();
            }
            ring.addLast(entry);
        }
        broadcast(entry);
    }

    public List<Map<String, Object>> recent(int n) {
        synchronized (ring) {
            List<Map<String, Object>> list = new ArrayList<>(ring);
            int from = Math.max(0, list.size() - Math.min(n, RING_SIZE));
            return list.subList(from, list.size());
        }
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    private void broadcast(Map<String, Object> entry) {
        if (emitters.isEmpty()) return;
        ObjectMapper om = objectMapper;
        if (om == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("log").data(entry, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
            }
        }
    }

    private static Map<String, Object> toMap(ILoggingEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", Instant.ofEpochMilli(e.getTimeStamp()).toString());
        m.put("level", e.getLevel().levelStr);
        m.put("logger", abbreviateLogger(e.getLoggerName()));
        m.put("msg", e.getFormattedMessage());
        if (e.getThrowableProxy() != null) {
            m.put("ex", e.getThrowableProxy().getClassName() + ": " + e.getThrowableProxy().getMessage());
        }
        return m;
    }

    private static String abbreviateLogger(String name) {
        if (name == null) return "";
        String[] parts = name.split("\\.");
        if (parts.length <= 3) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }
}
