package com.stokr.user.telegram.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.user.telegram.TelegramVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/telegram")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final TelegramVerificationService telegramVerificationService;

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret
    ) {
        telegramVerificationService.handleWebhook(body, secret);
        return ResponseEntity.ok().build();
    }
}
