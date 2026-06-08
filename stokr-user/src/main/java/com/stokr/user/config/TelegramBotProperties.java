package com.stokr.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stokr.telegram")
public class TelegramBotProperties {

    private String botToken = "";
    private String botUsername = "";
    private String webhookSecret = "";
    private boolean dryRun = true;
    private String operatorChatId = "";

    /** Master switch for operator (admin) Telegram alerts. */
    private boolean operatorAlertsEnabled = true;

    /** Send trader entry/exit fill alerts when Telegram is verified. */
    private boolean traderFillAlertsEnabled = true;

    /** Pre-market / pre-open live-readiness summary to operator. */
    private boolean operatorReadinessAlertsEnabled = true;

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getOperatorChatId() {
        return operatorChatId;
    }

    public void setOperatorChatId(String operatorChatId) {
        this.operatorChatId = operatorChatId;
    }

    public boolean isOperatorAlertsEnabled() {
        return operatorAlertsEnabled;
    }

    public void setOperatorAlertsEnabled(boolean operatorAlertsEnabled) {
        this.operatorAlertsEnabled = operatorAlertsEnabled;
    }

    public boolean isTraderFillAlertsEnabled() {
        return traderFillAlertsEnabled;
    }

    public void setTraderFillAlertsEnabled(boolean traderFillAlertsEnabled) {
        this.traderFillAlertsEnabled = traderFillAlertsEnabled;
    }

    public boolean isOperatorReadinessAlertsEnabled() {
        return operatorReadinessAlertsEnabled;
    }

    public void setOperatorReadinessAlertsEnabled(boolean operatorReadinessAlertsEnabled) {
        this.operatorReadinessAlertsEnabled = operatorReadinessAlertsEnabled;
    }
}
