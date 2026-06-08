package com.stokr.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stokr.broker.zerodha")
public class ZerodhaBrokerProperties {

    private String apiKey = "";
    private String apiSecret = "";
    /**
     * Registered Kite Connect redirect URL (must match app settings). Zerodha only accepts https:// — see
     * {@link com.stokr.user.broker.ZerodhaConnectionService#beginAuthorization}.
     */
    private String redirectUrl = "http://localhost:8080/api/broker/zerodha/callback";

    /**
     * When false, {@code POST /api/trader/broker/test-order} is rejected (safe default).
     */
    private boolean testOrderEnabled = false;

    /**
     * When true, validates session against Kite but does not place a live order (returns simulated id).
     */
    private boolean testOrderDryRun = true;

    /** Send operator Telegram with Kite login link when platform OAuth is required. */
    private boolean oauthAlertEnabled = true;

    /** Minimum minutes between repeated OAuth Telegram alerts. */
    private long oauthAlertCooldownMinutes = 240;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public void setRedirectUrl(String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }

    public boolean isTestOrderEnabled() {
        return testOrderEnabled;
    }

    public void setTestOrderEnabled(boolean testOrderEnabled) {
        this.testOrderEnabled = testOrderEnabled;
    }

    public boolean isTestOrderDryRun() {
        return testOrderDryRun;
    }

    public void setTestOrderDryRun(boolean testOrderDryRun) {
        this.testOrderDryRun = testOrderDryRun;
    }

    public boolean isOauthAlertEnabled() {
        return oauthAlertEnabled;
    }

    public void setOauthAlertEnabled(boolean oauthAlertEnabled) {
        this.oauthAlertEnabled = oauthAlertEnabled;
    }

    public long getOauthAlertCooldownMinutes() {
        return oauthAlertCooldownMinutes;
    }

    public void setOauthAlertCooldownMinutes(long oauthAlertCooldownMinutes) {
        this.oauthAlertCooldownMinutes = oauthAlertCooldownMinutes;
    }
}
