package com.stokr.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap-owned platform Zerodha websocket (lives in monolith alongside {@code stokr-user} session rows).
 */
@ConfigurationProperties(prefix = "stokr.platform.zerodha")
public class PlatformZerodhaFeedProperties {

    /** When false, no in-process websocket is started (OAuth row can still exist). */
    private boolean liveFeedEnabled = true;

    /**
     * When true (default), automatically fetches all NSE EQ instruments from Zerodha at connect time
     * and subscribes to every equity token. Overrides {@link #instrumentTokens} for NSE equities.
     */
    private boolean autoSubscribeAllNse = true;

    /**
     * When true, also fetches all MCX instruments and subscribes to them.
     * Requires valid access token with commodity segment access.
     */
    private boolean autoSubscribeMcx = false;

    /**
     * Fallback comma-separated Kite {@code instrument_token} values used only when
     * both autoSubscribeAllNse and autoSubscribeMcx are false.
     * Default: NIFTY 50 index for basic connectivity testing.
     */
    private String instrumentTokens = "256265";

    /**
     * Optional comma-separated canonical symbols aligned with {@link #instrumentTokens} by index.
     * Only used when autoSubscribeAllNse=false.
     */
    private String instrumentSymbols = "";

    public boolean isLiveFeedEnabled() { return liveFeedEnabled; }
    public void setLiveFeedEnabled(boolean liveFeedEnabled) { this.liveFeedEnabled = liveFeedEnabled; }

    public boolean isAutoSubscribeAllNse() { return autoSubscribeAllNse; }
    public void setAutoSubscribeAllNse(boolean autoSubscribeAllNse) { this.autoSubscribeAllNse = autoSubscribeAllNse; }

    public boolean isAutoSubscribeMcx() { return autoSubscribeMcx; }
    public void setAutoSubscribeMcx(boolean autoSubscribeMcx) { this.autoSubscribeMcx = autoSubscribeMcx; }

    public String getInstrumentTokens() { return instrumentTokens; }
    public void setInstrumentTokens(String instrumentTokens) { this.instrumentTokens = instrumentTokens; }

    public String getInstrumentSymbols() { return instrumentSymbols; }
    public void setInstrumentSymbols(String instrumentSymbols) { this.instrumentSymbols = instrumentSymbols; }

    public List<Integer> parsedInstrumentTokens() {
        List<Integer> out = new ArrayList<>();
        if (instrumentTokens == null || instrumentTokens.isBlank()) {
            out.add(256265);
            return out;
        }
        for (String part : instrumentTokens.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(256265);
        return out;
    }

    public List<String> parsedInstrumentSymbols() {
        List<String> out = new ArrayList<>();
        if (instrumentSymbols == null || instrumentSymbols.isBlank()) return out;
        for (String part : instrumentSymbols.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
