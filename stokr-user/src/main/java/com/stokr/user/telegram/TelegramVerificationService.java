package com.stokr.user.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.events.auth.AuthAuditEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.config.TelegramBotProperties;
import com.stokr.user.domain.AuthTelegramVerificationToken;
import com.stokr.user.onboarding.TraderOnboardingService;
import com.stokr.user.orchestration.TraderOrchestrationService;
import com.stokr.user.repository.AuthTelegramVerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthTelegramVerificationTokenRepository tokenRepository;
    private final AuthUserRepository authUserRepository;
    private final TelegramBotProperties telegramBotProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final TraderOnboardingService traderOnboardingService;
    private final TraderOrchestrationService traderOrchestrationService;

    public TelegramLinkDto createVerificationLink(UUID userId) {
        AuthUser user = authUserRepository.findById(userId).orElseThrow(() -> new BadRequestException("User not found"));
        if (!StringUtils.hasText(user.getTelegramUsername())) {
            throw new BadRequestException("Set your Telegram @username on your profile first");
        }
        if (!StringUtils.hasText(telegramBotProperties.getBotUsername())) {
            throw new BadRequestException("Telegram bot is not configured");
        }
        String raw = generateRawToken();
        AuthTelegramVerificationToken t = new AuthTelegramVerificationToken();
        t.setUser(user);
        t.setTokenHash(sha256Hex(raw));
        t.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        t.setUsed(false);
        tokenRepository.save(t);
        String deepLink = "https://t.me/" + telegramBotProperties.getBotUsername().replaceFirst("^@", "") + "?start=" + raw;
        return new TelegramLinkDto(deepLink, t.getExpiresAt());
    }

    @Transactional
    public void handleWebhook(JsonNode body, String secretHeader) {
        if (StringUtils.hasText(telegramBotProperties.getWebhookSecret())
                && !telegramBotProperties.getWebhookSecret().equals(secretHeader)) {
            log.warn("telegram.webhook.bad_secret");
            return;
        }
        JsonNode message = body.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return;
        }
        String text = message.path("text").asText("");
        long chatId = message.path("chat").path("id").asLong(0L);
        if (chatId == 0L) {
            return;
        }
        if (!text.startsWith("/start")) {
            return;
        }
        String arg = text.length() > 6 ? text.substring(6).trim() : "";
        if (arg.isEmpty()) {
            return;
        }
        AuthTelegramVerificationToken token = tokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(sha256Hex(arg), Instant.now())
                .orElse(null);
        if (token == null) {
            log.warn("telegram.verify.unknown_token");
            return;
        }
        token.setUsed(true);
        tokenRepository.save(token);
        AuthUser user = token.getUser();
        user.setTelegramVerified(true);
        user.setTelegramChatId(Long.toString(chatId));
        authUserRepository.save(user);
        eventPublisher.publishEvent(new AuthAuditEvents.TelegramVerified(user.getId(), user.getTelegramChatId(), Instant.now()));
    }

    private static String generateRawToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record TelegramLinkDto(String deepLink, Instant expiresAt) {
    }
}
