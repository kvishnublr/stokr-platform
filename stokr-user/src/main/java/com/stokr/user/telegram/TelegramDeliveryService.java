package com.stokr.user.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.events.ExecutionAlertEvent;
import com.stokr.common.notification.NotificationEvent;
import com.stokr.user.config.TelegramBotProperties;
import com.stokr.user.domain.NotificationDeliveryRecord;
import com.stokr.user.repository.NotificationDeliveryRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramDeliveryService {

    private final AuthUserRepository authUserRepository;
    private final TelegramBotClient telegramBotClient;
    private final TelegramBotProperties telegramBotProperties;
    private final NotificationDeliveryRecordRepository deliveryRecordRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void deliver(NotificationEvent event) {
        if (event.userId() == null) {
            return;
        }
        AuthUser user = authUserRepository.findById(event.userId()).orElse(null);
        if (user == null || !user.isTelegramVerified() || user.getTelegramChatId() == null || user.getTelegramChatId().isBlank()) {
            record(event, "SKIPPED", "no_chat", null);
            return;
        }
        String text = render(event.templateKey(), event.payload());
        boolean ok = telegramBotClient.sendMessage(user.getTelegramChatId(), text);
        record(event, ok ? "DELIVERED" : "FAILED", ok ? null : "telegram_api", null);
    }

    private void record(NotificationEvent event, String status, String error, Throwable cause) {
        try {
            NotificationDeliveryRecord r = new NotificationDeliveryRecord();
            r.setUserId(event.userId());
            r.setChannel("TELEGRAM");
            r.setTemplateKey(event.templateKey());
            r.setStatus(status);
            r.setAttempts(1);
            r.setLastError(error != null ? error : (cause != null ? cause.getMessage() : null));
            r.setPayloadJson(objectMapper.writeValueAsString(event.payload()));
            r.setCorrelationId(CorrelationIdHolder.get());
            if ("DELIVERED".equals(status)) {
                r.setDeliveredAt(Instant.now());
            }
            deliveryRecordRepository.save(r);
        } catch (Exception ex) {
            log.warn("notification.delivery.persist_failed {}", ex.toString());
        }
    }

    private static String render(String templateKey, Map<String, String> payload) {
        String p = payload != null ? payload.toString() : "{}";
        return switch (templateKey != null ? templateKey : "") {
            case "TRADER_ELIGIBILITY_BLOCK" -> "🛑 Trading blocked: " + payloadOr(payload, "message", "unknown reason");
            case "ORDER_FILLED" -> "✅ Order filled " + payloadOr(payload, "symbol", "") + " " + payloadOr(payload, "state", "");
            case "RUNTIME_STATE" -> "⚙️ Strategy runtime: " + payloadOr(payload, "runtimeState", "") + " (" + payloadOr(payload, "instanceId", "") + ")";
            case "RISK_REJECT" -> "⛔ Risk: " + payloadOr(payload, "message", "rejected");
            default -> "📣 Stokr: " + templateKey + " " + p;
        };
    }

    private static String payloadOr(Map<String, String> payload, String key, String def) {
        if (payload == null) {
            return def;
        }
        String v = payload.get(key);
        return v != null ? v : def;
    }

    @EventListener
    public void onExecutionAlert(ExecutionAlertEvent event) {
        sendToOperator(event.alertType(), event.text());
    }

    public boolean sendToOperator(String alertType, String text) {
        String chatId = telegramBotProperties.getOperatorChatId();
        if (chatId == null || chatId.isBlank()) {
            log.debug("operator_chat_id not configured — skipping alert type={}", alertType);
            return false;
        }
        boolean sent = telegramBotClient.sendMessage(chatId, text);
        if (sent) {
            log.info("telegram.operator_alert.sent type={}", alertType);
        } else {
            log.warn("telegram.operator_alert.failed type={}", alertType);
        }
        return sent;
    }
}
