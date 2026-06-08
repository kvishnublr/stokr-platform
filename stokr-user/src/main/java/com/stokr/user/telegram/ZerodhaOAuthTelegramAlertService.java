package com.stokr.user.telegram;

import com.stokr.common.events.PlatformZerodhaOAuthRequiredEvent;
import com.stokr.common.events.PlatformZerodhaOAuthResolvedEvent;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.config.TelegramBotProperties;
import com.stokr.user.config.ZerodhaBrokerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends operator Telegram alerts with a mobile-friendly Kite login link when platform Zerodha OAuth is required.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZerodhaOAuthTelegramAlertService {

    private final TelegramBotClient telegramBotClient;
    private final TelegramBotProperties telegramBotProperties;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final PlatformMarketFeedService platformMarketFeedService;

    private final AtomicReference<Instant> lastOAuthAlertAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastResolvedAlertAt = new AtomicReference<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOAuthRequired(PlatformZerodhaOAuthRequiredEvent event) {
        if (!zerodhaBrokerProperties.isOauthAlertEnabled()) {
            return;
        }
        if (!zerodhaBrokerProperties.isConfigured()) {
            return;
        }
        String chatId = telegramBotProperties.getOperatorChatId();
        if (chatId == null || chatId.isBlank()) {
            log.debug("zerodha.oauth_alert skipped reason=no_operator_chat_id");
            return;
        }
        if (!platformMarketFeedService.platformSessionRequiresOAuth()) {
            return;
        }
        if (!cooldownElapsed(lastOAuthAlertAt, zerodhaBrokerProperties.getOauthAlertCooldownMinutes())) {
            log.debug("zerodha.oauth_alert skipped reason=cooldown");
            return;
        }

        Map<String, Object> connect = platformMarketFeedService.beginZerodhaPlatformConnect();
        String authorizeUrl = String.valueOf(connect.get("authorizeUrl"));
        String reason = event.reason() != null ? event.reason() : "oauth_required";
        String html = """
                🔐 <b>Zerodha reconnect required</b>

                Platform market feed needs login. Tap the link below on your phone — after Kite login, tokens refresh automatically (no admin UI needed).

                <a href="%s">Connect Zerodha on Kite</a>

                Reason: %s
                """.formatted(escapeHtml(authorizeUrl), escapeHtml(reason));

        boolean sent = telegramBotClient.sendHtmlMessage(chatId, html);
        if (sent) {
            lastOAuthAlertAt.set(Instant.now());
            log.info("zerodha.oauth_alert.sent reason={}", reason);
        } else {
            log.warn("zerodha.oauth_alert.failed reason={}", reason);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOAuthResolved(PlatformZerodhaOAuthResolvedEvent event) {
        if (!zerodhaBrokerProperties.isOauthAlertEnabled()) {
            return;
        }
        String chatId = telegramBotProperties.getOperatorChatId();
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        if (!cooldownElapsed(lastResolvedAlertAt, 30)) {
            return;
        }
        String html = "✅ <b>Zerodha connected</b>\n\nPlatform feed tokens saved. Market data should resume shortly.";
        if (telegramBotClient.sendHtmlMessage(chatId, html)) {
            lastResolvedAlertAt.set(Instant.now());
            lastOAuthAlertAt.set(null);
            log.info("zerodha.oauth_resolved_alert.sent");
        }
    }

    /** Periodic safety net — catches AUTH_EXPIRED without a recent refresh attempt. */
    public void pollAndAlertIfNeeded() {
        if (!zerodhaBrokerProperties.isOauthAlertEnabled() || !zerodhaBrokerProperties.isConfigured()) {
            return;
        }
        if (!platformMarketFeedService.platformSessionRequiresOAuth()) {
            return;
        }
        onOAuthRequired(new PlatformZerodhaOAuthRequiredEvent("ZERODHA", "scheduled_watch", Instant.now()));
    }

    private static boolean cooldownElapsed(AtomicReference<Instant> lastAt, long cooldownMinutes) {
        Instant last = lastAt.get();
        if (last == null) {
            return true;
        }
        return Duration.between(last, Instant.now()).toMinutes() >= Math.max(1, cooldownMinutes);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
