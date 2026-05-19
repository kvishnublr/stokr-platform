package com.stokr.admin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.admin.log.SseLogAppender;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminLogStreamController {

    private final ObjectMapper objectMapper;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public SseEmitter logStream(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        SseLogAppender appender = SseLogAppender.getInstance();
        if (appender == null) {
            SseEmitter dead = new SseEmitter(0L);
            dead.complete();
            return dead;
        }
        appender.setObjectMapper(objectMapper);
        return appender.subscribe();
    }

    @GetMapping("/recent")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> recentLogs(@RequestParam(defaultValue = "200") int n) {
        SseLogAppender appender = SseLogAppender.getInstance();
        if (appender == null) {
            return Map.of("data", Collections.emptyList());
        }
        List<Map<String, Object>> logs = appender.recent(Math.min(n, 500));
        return Map.of("data", logs);
    }
}
