package com.stokr.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminTestSignalLabRemediationService {

    private final AdminBrokerOrchestrationService brokerOrchestrationService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${stokr.admin.test-lab.hooks.redis-restart-url:}")
    private String redisRestartHookUrl;

    @Value("${stokr.admin.test-lab.hooks.queue-restart-url:}")
    private String queueRestartHookUrl;

    public Map<String, Object> remediate(String actionCode, UUID traderUserId, String vendor) {
        String code = actionCode == null ? "" : actionCode.trim().toUpperCase();
        return switch (code) {
            case "RECONNECT_BROKER" -> reconnectBroker(traderUserId);
            case "RESTART_REDIS_HOOK" -> invokeHook("redis-restart", redisRestartHookUrl);
            case "RESTART_QUEUE_HOOK" -> invokeHook("queue-restart", queueRestartHookUrl);
            default -> Map.of(
                    "ok", false,
                    "actionCode", code,
                    "message", "Unknown remediation action"
            );
        };
    }

    private Map<String, Object> reconnectBroker(UUID traderUserId) {
        if (traderUserId == null) {
            return Map.of("ok", false, "message", "traderUserId required for reconnect");
        }
        Map<String, Object> result = brokerOrchestrationService.zerodhaTestSession(traderUserId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("actionCode", "RECONNECT_BROKER");
        out.put("at", Instant.now().toString());
        out.put("result", result);
        return out;
    }

    private Map<String, Object> invokeHook(String name, String url) {
        if (url == null || url.isBlank()) {
            return Map.of(
                    "ok", false,
                    "actionCode", name,
                    "message", "Hook URL not configured"
            );
        }
        ResponseEntity<String> response = restTemplate.postForEntity(url, Map.of("action", name, "at", Instant.now().toString()), String.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", response.getStatusCode().is2xxSuccessful());
        out.put("actionCode", name);
        out.put("status", response.getStatusCode().value());
        out.put("body", response.getBody());
        return out;
    }
}
