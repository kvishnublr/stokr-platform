package com.stokr.user.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.user.config.TelegramBotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Minimal Telegram Bot API client (sendMessage).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramBotClient {

    private final TelegramBotProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient http = RestClient.builder().build();

    /**
     * @return true if Telegram accepted the message (or dry-run).
     */
    public boolean sendMessage(String chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public boolean sendHtmlMessage(String chatId, String html) {
        return sendMessage(chatId, html, "HTML");
    }

    private boolean sendMessage(String chatId, String text, String parseMode) {
        if (properties.isDryRun()) {
            log.info("[telegram-dry-run] chatId={} parseMode={} text={}", chatId, parseMode, text);
            return true;
        }
        if (properties.getBotToken() == null || properties.getBotToken().isBlank()) {
            log.warn("Telegram bot token not configured — skipping send");
            return false;
        }
        try {
            String url = "https://api.telegram.org/bot" + properties.getBotToken() + "/sendMessage";
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            payload.put("disable_web_page_preview", true);
            if (parseMode != null && !parseMode.isBlank()) {
                payload.put("parse_mode", parseMode);
            }
            String body = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body != null ? body : "{}");
            return root.path("ok").asBoolean(false);
        } catch (Exception e) {
            log.warn("telegram.send.failed {}", e.toString());
            return false;
        }
    }
}
