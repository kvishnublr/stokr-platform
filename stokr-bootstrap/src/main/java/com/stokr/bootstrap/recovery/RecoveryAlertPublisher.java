package com.stokr.bootstrap.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecoveryAlertPublisher {

    private final PlatformRecoveryProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient http = RestClient.builder().build();

    public void publishEscalation(
            OperationalRecoveryContext ctx,
            OperationalFailureSignature signature,
            RecoveryActionType lastAction,
            OperationalRecoveryState state,
            Map<String, Object> actionResult
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "platform.recovery.escalation");
        payload.put("serviceKey", properties.getServiceKey());
        payload.put("signature", signature != null ? signature.name() : "UNKNOWN");
        payload.put("lastAction", lastAction != null ? lastAction.name() : RecoveryActionType.NONE.name());
        payload.put("attemptCount", state.attemptCount());
        payload.put("consecutiveUnhealthyCycles", state.consecutiveUnhealthyCycles());
        payload.put("collectedAt", ctx.collectedAt().toString());
        payload.put("errorSignatures", ctx.errorSignatures());
        payload.put("recentLogLines", ctx.recentLogLines());
        payload.put("actionResult", actionResult);
        payload.put("recoveryState", Map.of(
                "lastSuccessAt", state.lastSuccessAt() != null ? state.lastSuccessAt().toString() : null,
                "lastActionAt", state.lastActionAt() != null ? state.lastActionAt().toString() : null
        ));

        try {
            String json = objectMapper.writeValueAsString(payload);
            log.error("platform.recovery.escalation payload={}", json);
            dispatchWebhook(json);
        } catch (Exception ex) {
            log.error("platform.recovery.escalation_log_failed {}", ex.toString());
        }
    }

    public void publishResolved(OperationalRecoveryContext ctx, OperationalRecoveryState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "platform.recovery.resolved");
        payload.put("serviceKey", properties.getServiceKey());
        payload.put("collectedAt", ctx.collectedAt().toString());
        payload.put("lastSuccessAt", state.lastSuccessAt() != null ? state.lastSuccessAt().toString() : null);
        try {
            log.info("platform.recovery.resolved payload={}", objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            log.info("platform.recovery.resolved serviceKey={}", properties.getServiceKey());
        }
    }

    private void dispatchWebhook(String json) {
        String url = properties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("platform.recovery.webhook_failed url={} err={}", url, ex.toString());
        }
    }
}
