package com.stokr.user.broker;

import com.stokr.user.config.TelegramBotProperties;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.telegram.TelegramBotClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Daily OAuth validation scheduler - sends Zerodha login link to trader and admin at 6 AM IST.
 *
 * Runs daily at 6:00 AM Asia/Kolkata timezone.
 * Sends validation links to:
 * - Trader user (kvishnu.blr@gmail.com)
 * - Admin user (platform admin)
 *
 * Validates that OAuth tokens are still active and accessible.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaOAuthDailyValidationScheduler {

    private final PlatformMarketFeedService platformMarketFeedService;
    private final TelegramBotClient telegramBotClient;
    private final TelegramBotProperties telegramBotProperties;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;

    // Trader user ID (primary trader)
    private static final UUID TRADER_USER_ID = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    /**
     * Run daily at 6:00 AM IST (00:30 UTC)
     * Cron: 0 0 6 * * * (6 AM every day)
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void validateOAuthDaily() {
        try {
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("ZERODHA DAILY OAUTH VALIDATION - Starting");
            log.info("═══════════════════════════════════════════════════════════════");

            if (!zerodhaBrokerProperties.isConfigured()) {
                log.warn("Zerodha not configured, skipping validation");
                return;
            }

            // Check if OAuth is needed
            boolean oauthRequired = platformMarketFeedService.platformSessionRequiresOAuth();
            log.info("oauth_status requiresAuth={}", oauthRequired);

            // Generate OAuth URL
            Map<String, Object> connectResult = platformMarketFeedService.beginZerodhaPlatformConnect();
            String authorizeUrl = String.valueOf(connectResult.get("authorizeUrl"));

            // Send to trader
            sendValidationLinkToTrader(authorizeUrl, oauthRequired);

            // Send to admin
            sendValidationLinkToAdmin(authorizeUrl, oauthRequired);

            log.info("═══════════════════════════════════════════════════════════════");
            log.info("ZERODHA DAILY OAUTH VALIDATION - Complete");
            log.info("═══════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("Fatal error in daily OAuth validation: {}", e.getMessage(), e);
        }
    }

    /**
     * Send validation link to trader via Telegram.
     */
    private void sendValidationLinkToTrader(String authorizeUrl, boolean oauthRequired) {
        try {
            String traderChatId = telegramBotProperties.getTraderChatId();

            if (traderChatId == null || traderChatId.isBlank()) {
                log.warn("Trader Telegram chat ID not configured");
                return;
            }

            String status = oauthRequired ? "🔴 ACTION REQUIRED" : "✅ VALID";
            String actionText = oauthRequired
                ? "Your Zerodha session has expired. Tap to re-authenticate:"
                : "Your Zerodha session is active. For security, re-authenticate daily:";

            String html = """
                    %s <b>Zerodha OAuth Daily Validation</b>

                    %s

                    <a href="%s">Connect Zerodha on Kite</a>

                    Time: %s
                    User: TRADER
                    """.formatted(
                        status,
                        actionText,
                        escapeHtml(authorizeUrl),
                        Instant.now().toString()
                    );

            if (telegramBotClient.sendHtmlMessage(traderChatId, html)) {
                log.info("oauth_validation.trader_link.sent timestamp={}", Instant.now());
            } else {
                log.warn("oauth_validation.trader_link.failed");
            }

        } catch (Exception e) {
            log.error("Error sending validation link to trader: {}", e.getMessage(), e);
        }
    }

    /**
     * Send validation link to admin via Telegram.
     */
    private void sendValidationLinkToAdmin(String authorizeUrl, boolean oauthRequired) {
        try {
            String adminChatId = telegramBotProperties.getAdminChatId();

            if (adminChatId == null || adminChatId.isBlank()) {
                log.warn("Admin Telegram chat ID not configured");
                return;
            }

            String status = oauthRequired ? "🔴 ACTION REQUIRED" : "✅ VALID";
            String actionText = oauthRequired
                ? "Platform Zerodha session has expired. Tap to re-authenticate:"
                : "Platform Zerodha session is active. For security, re-authenticate daily:";

            String html = """
                    %s <b>Zerodha OAuth Daily Validation</b>

                    %s

                    <a href="%s">Connect Zerodha on Kite</a>

                    Time: %s
                    User: ADMIN
                    """.formatted(
                        status,
                        actionText,
                        escapeHtml(authorizeUrl),
                        Instant.now().toString()
                    );

            if (telegramBotClient.sendHtmlMessage(adminChatId, html)) {
                log.info("oauth_validation.admin_link.sent timestamp={}", Instant.now());
            } else {
                log.warn("oauth_validation.admin_link.failed");
            }

        } catch (Exception e) {
            log.error("Error sending validation link to admin: {}", e.getMessage(), e);
        }
    }

    /**
     * Escape HTML special characters for Telegram HTML formatting.
     */
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
