package com.stokr.user.telegram;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.events.ExecutionAlertEvent;
import com.stokr.common.notification.NotificationEvent;
import com.stokr.user.config.TelegramBotProperties;
import com.stokr.user.domain.NotificationDeliveryRecord;
import com.stokr.user.repository.NotificationDeliveryRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramDeliveryService {

    /** Operator gets risk/system alerts only — not per-trade fills (those go to traders). */
    private static final Set<String> OPERATOR_ALERT_TYPES = Set.of(
            "RISK_REJECTED",
            "BROKER_REJECTED",
            "DAILY_LOSS_BREACH",
            "EMERGENCY_STOP"
    );

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
        if (!shouldDeliverTraderTemplate(event.templateKey())) {
            record(event, "SKIPPED", "template_filtered", null);
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

    @Transactional
    public void deliverTraderTradeFill(UUID userId, String executionMode, boolean exit, Map<String, String> fields) {
        if (!telegramBotProperties.isTraderFillAlertsEnabled() || userId == null) {
            return;
        }
        if (executionMode == null || (!"LIVE".equalsIgnoreCase(executionMode) && !"PAPER".equalsIgnoreCase(executionMode))) {
            return;
        }
        String templateKey = exit ? "TRADE_EXIT" : "TRADE_ENTRY";
        Map<String, String> payload = new LinkedHashMap<>(fields != null ? fields : Map.of());
        payload.putIfAbsent("mode", executionMode.toUpperCase());
        deliver(new NotificationEvent("TELEGRAM", templateKey, userId, payload));
    }

    public boolean sendOperatorHtml(String alertType, String html) {
        if (!telegramBotProperties.isOperatorAlertsEnabled()) {
            return false;
        }
        String chatId = telegramBotProperties.getOperatorChatId();
        if (chatId == null || chatId.isBlank()) {
            return false;
        }
        boolean sent = telegramBotClient.sendHtmlMessage(chatId, html);
        if (sent) {
            log.info("telegram.operator_alert.sent type={}", alertType);
        }
        return sent;
    }

    public boolean sendOperatorPlain(String alertType, String text) {
        if (!telegramBotProperties.isOperatorAlertsEnabled()) {
            return false;
        }
        String chatId = telegramBotProperties.getOperatorChatId();
        if (chatId == null || chatId.isBlank()) {
            return false;
        }
        boolean sent = telegramBotClient.sendMessage(chatId, text);
        if (sent) {
            log.info("telegram.operator_alert.sent type={}", alertType);
        }
        return sent;
    }

    public boolean sendTraderPlain(UUID userId, String text) {
        if (userId == null) {
            return false;
        }
        AuthUser user = authUserRepository.findById(userId).orElse(null);
        if (user == null || !user.isTelegramVerified() || user.getTelegramChatId() == null || user.getTelegramChatId().isBlank()) {
            return false;
        }
        return telegramBotClient.sendMessage(user.getTelegramChatId(), text);
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

    private static boolean shouldDeliverTraderTemplate(String templateKey) {
        if (templateKey == null) {
            return false;
        }
        return switch (templateKey) {
            case "TRADE_ENTRY", "TRADE_EXIT", "TRADER_ELIGIBILITY_BLOCK", "RISK_REJECT" -> true;
            default -> false;
        };
    }

    private static String render(String templateKey, Map<String, String> payload) {
        return switch (templateKey != null ? templateKey : "") {
            case "TRADE_ENTRY" -> """
                    📈 Entry filled (%s)
                    %s %s x %s @ %s
                    Strategy: %s""".formatted(
                    payloadOr(payload, "mode", "LIVE"),
                    payloadOr(payload, "side", ""),
                    payloadOr(payload, "symbol", ""),
                    payloadOr(payload, "qty", ""),
                    payloadOr(payload, "price", ""),
                    payloadOr(payload, "strategy", ""));
            case "TRADE_EXIT" -> """
                    📉 Exit filled (%s)
                    %s %s x %s @ %s
                    Strategy: %s""".formatted(
                    payloadOr(payload, "mode", "LIVE"),
                    payloadOr(payload, "side", ""),
                    payloadOr(payload, "symbol", ""),
                    payloadOr(payload, "qty", ""),
                    payloadOr(payload, "price", ""),
                    payloadOr(payload, "strategy", ""));
            case "TRADER_ELIGIBILITY_BLOCK" -> "🛑 Trading blocked: " + payloadOr(payload, "message", "unknown reason");
            case "RISK_REJECT" -> "⛔ Risk: " + payloadOr(payload, "message", "rejected");
            default -> "📣 Stokr: " + templateKey;
        };
    }

    private static String payloadOr(Map<String, String> payload, String key, String def) {
        if (payload == null) {
            return def;
        }
        String v = payload.get(key);
        return v != null && !v.isBlank() ? v : def;
    }

    @EventListener
    public void onExecutionAlert(ExecutionAlertEvent event) {
        if (!OPERATOR_ALERT_TYPES.contains(event.alertType())) {
            log.debug("telegram.operator_alert.skipped type={}", event.alertType());
            return;
        }
        sendOperatorPlain(event.alertType(), event.text());
    }
}
