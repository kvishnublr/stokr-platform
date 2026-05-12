package com.stokr.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stokr.broker.zerodha")
public class ZerodhaBrokerProperties {

    private String apiKey = "";
    private String apiSecret = "";
    /**
     * Registered Kite Connect redirect URL (must match app settings).
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
}
