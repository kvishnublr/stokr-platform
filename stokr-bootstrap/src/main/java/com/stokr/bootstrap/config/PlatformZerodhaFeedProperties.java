package com.stokr.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap-owned platform Zerodha websocket (lives in monolith alongside {@code stokr-user} session rows).
 */
@ConfigurationProperties(prefix = "stokr.platform.zerodha")
public class PlatformZerodhaFeedProperties {

    /**
     * When false, no in-process websocket is started (OAuth row can still exist).
     */
    private boolean liveFeedEnabled = true;

    /**
     * Comma-separated Kite {@code instrument_token} values (NSE) for LTP subscriptions.
     * Default includes NIFTY 50 index so connectivity proves out without custom instrument work.
     */
    private String instrumentTokens = "256265";

    public boolean isLiveFeedEnabled() {
        return liveFeedEnabled;
    }

    public void setLiveFeedEnabled(boolean liveFeedEnabled) {
        this.liveFeedEnabled = liveFeedEnabled;
    }

    public String getInstrumentTokens() {
        return instrumentTokens;
    }

    public void setInstrumentTokens(String instrumentTokens) {
        this.instrumentTokens = instrumentTokens;
    }

    public List<Integer> parsedInstrumentTokens() {
        List<Integer> out = new ArrayList<>();
        if (instrumentTokens == null || instrumentTokens.isBlank()) {
            out.add(256265);
            return out;
        }
        for (String part : instrumentTokens.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // skip invalid fragment
            }
        }
        if (out.isEmpty()) {
            out.add(256265);
        }
        return out;
    }
}
