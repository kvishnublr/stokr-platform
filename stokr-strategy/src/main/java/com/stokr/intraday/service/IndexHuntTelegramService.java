package com.stokr.intraday.service;

import com.stokr.intraday.domain.IndexSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Telegram Alert Service for INDEX HUNT
 * Sends signal notifications and outcome updates to configured chat
 *
 * Environment Variables:
 * - TELEGRAM_BOT_TOKEN: Bot token from BotFather
 * - TELEGRAM_CHAT_ID_VISHNUBLR: Chat ID for Vishnu (main trader)
 * - TELEGRAM_CHAT_ID_HARSHVTRADE: Chat ID for Harsh (secondary)
 */
@Service
@Slf4j
public class IndexHuntTelegramService {

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.chat.id.vishnublr:}")
    private String chatIdVishnublr;

    @Value("${telegram.chat.id.harshvtrade:}")
    private String chatIdHarshvtrade;

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Send new signal alert
     */
    public void sendSignalAlert(IndexSignal signal) {
        if (!isConfigured()) {
            log.warn("TELEGRAM.not_configured - skipping signal alert");
            return;
        }

        try {
            String message = formatSignalMessage(signal);
            sendMessage(message);
            log.info("TELEGRAM.signal_sent signal_id={}", signal.getSignalId());
        } catch (Exception e) {
            log.error("TELEGRAM.send_error signal_id={} error={}", signal.getSignalId(), e.getMessage());
        }
    }

    /**
     * Send T1 hit (WIN) alert
     */
    public void sendWinAlert(Long signalId, String indexName, String direction,
                            BigDecimal entry, BigDecimal exit, BigDecimal pnl) {
        if (!isConfigured()) return;

        try {
            String message = String.format(
                    "??? *T1 HIT - WIN*\n" +
                    "Index: %s %s\n" +
                    "Entry: ???%.2f\n" +
                    "Exit: ???%.2f\n" +
                    "P&L: ???%.2f\n" +
                    "Time: %s",
                    indexName, direction,
                    entry, exit, pnl,
                    TIME_FORMATTER.format(Instant.now())
            );
            sendMessage(message);
            log.info("TELEGRAM.win_sent signal_id={}", signalId);
        } catch (Exception e) {
            log.error("TELEGRAM.win_send_error signal_id={} error={}", signalId, e.getMessage());
        }
    }

    /**
     * Send SL hit (LOSS) alert
     */
    public void sendLossAlert(Long signalId, String indexName, String direction,
                             BigDecimal entry, BigDecimal exit, BigDecimal loss) {
        if (!isConfigured()) return;

        try {
            String message = String.format(
                    "??? *SL HIT - LOSS*\n" +
                    "Index: %s %s\n" +
                    "Entry: ???%.2f\n" +
                    "Exit: ???%.2f\n" +
                    "Loss: ???%.2f\n" +
                    "Time: %s",
                    indexName, direction,
                    entry, exit, loss,
                    TIME_FORMATTER.format(Instant.now())
            );
            sendMessage(message);
            log.info("TELEGRAM.loss_sent signal_id={}", signalId);
        } catch (Exception e) {
            log.error("TELEGRAM.loss_send_error signal_id={} error={}", signalId, e.getMessage());
        }
    }

    /**
     * Send daily summary
     */
    public void sendDailySummary(PaperTradingExecutor.PaperTradingStats stats) {
        if (!isConfigured()) return;

        try {
            String message = String.format(
                    "???? *DAILY SUMMARY - INDEX HUNT*\n" +
                    "Trades: %d\n" +
                    "Wins: %d | Losses: %d\n" +
                    "Win Rate: %.1f%%\n" +
                    "Total P&L: ???%.2f\n" +
                    "Active Trades: %d\n" +
                    "Time: %s",
                    stats.totalTrades,
                    stats.wins, stats.losses,
                    stats.winRate,
                    stats.totalPnL,
                    stats.activeTrades,
                    TIME_FORMATTER.format(Instant.now())
            );
            sendMessage(message);
            log.info("TELEGRAM.daily_summary_sent");
        } catch (Exception e) {
            log.error("TELEGRAM.summary_send_error error={}", e.getMessage());
        }
    }

    /**
     * Format signal into readable message
     */
    private String formatSignalMessage(IndexSignal signal) {
        String strength = "hi".equals(signal.getSignalStrength()) ? "???? HIGH" : "???? MEDIUM";
        String direction = "CE".equals(signal.getDirection()) ? "???? CALL" : "???? PUT";
        String tier = signal.getQualityScore().compareTo(BigDecimal.valueOf(76)) >= 0 ? "??? PREMIUM" : "??? GOOD";

        return String.format(
                "???? *NEW INDEX HUNT SIGNAL*\n\n" +
                "Index: %s\n" +
                "Direction: %s\n" +
                "Quality: %.1f %s\n" +
                "Strength: %s\n\n" +
                "Entry: ???%.2f\n" +
                "SL: ???%.2f\n" +
                "T1: ???%.2f\n" +
                "T2: ???%.2f\n\n" +
                "Trend 30m: %.2f%%\n" +
                "Momentum 5m: %.2f%%\n" +
                "PCR: %.2f\n" +
                "VIX: %.2f\n\n" +
                "Time: %s",
                signal.getIndexName(),
                direction,
                signal.getQualityScore(), tier,
                strength,
                signal.getOptionEntryPremium(),
                signal.getOptionSL(),
                signal.getOptionT1(),
                signal.getOptionT2(),
                signal.getTrend30m(),
                signal.getMomentum5m(),
                signal.getPcrRatio(),
                signal.getVixLevel(),
                TIME_FORMATTER.format(signal.getTimeDetected())
        );
    }

    /**
     * Send raw message to Telegram
     */
    private void sendMessage(String text) {
        if (!isConfigured()) {
            log.warn("TELEGRAM.bot_token_missing - cannot send message");
            return;
        }

        try {
            // Send to Vishnu's chat
            if (chatIdVishnublr != null && !chatIdVishnublr.isEmpty()) {
                sendToChat(chatIdVishnublr, text);
            }

            // Send to Harsh's chat
            if (chatIdHarshvtrade != null && !chatIdHarshvtrade.isEmpty()) {
                sendToChat(chatIdHarshvtrade, text);
            }
        } catch (Exception e) {
            log.error("TELEGRAM.send_message_error error={}", e.getMessage());
        }
    }

    /**
     * Send message to specific chat ID
     */
    private void sendToChat(String chatId, String text) {
        try {
            String url = String.format("%s%s/sendMessage", TELEGRAM_API_URL, botToken);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("chat_id", chatId);
            requestBody.put("text", text);
            requestBody.put("parse_mode", "Markdown");

            restTemplate.postForObject(url, requestBody, Map.class);

            log.debug("TELEGRAM.message_sent chat_id={}", chatId);
        } catch (Exception e) {
            log.warn("TELEGRAM.send_to_chat_failed chat_id={} error={}", chatId, e.getMessage());
        }
    }

    /**
     * Check if Telegram is configured
     */
    private boolean isConfigured() {
        return botToken != null && !botToken.isEmpty() &&
                ((chatIdVishnublr != null && !chatIdVishnublr.isEmpty()) ||
                 (chatIdHarshvtrade != null && !chatIdHarshvtrade.isEmpty()));
    }

    /**
     * Test connection (health check)
     */
    public boolean testConnection() {
        try {
            String url = String.format("%s%s/getMe", TELEGRAM_API_URL, botToken);
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && (Boolean) response.getOrDefault("ok", false)) {
                log.info("TELEGRAM.connection_ok");
                return true;
            }
        } catch (Exception e) {
            log.error("TELEGRAM.connection_failed error={}", e.getMessage());
        }
        return false;
    }
}
