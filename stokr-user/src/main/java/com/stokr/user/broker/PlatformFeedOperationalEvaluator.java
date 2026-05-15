package com.stokr.user.broker;

import com.stokr.common.market.LiveMarketPathAssessment;

import java.time.Instant;
import java.util.Map;

/**
 * Single place for interpreting {@link PlatformMarketFeedService#infrastructureSnapshot()} vendor rows
 * for admin lifecycle + scanner gating.
 */
public final class PlatformFeedOperationalEvaluator {

    private PlatformFeedOperationalEvaluator() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> zerodhaVendorRow(Map<String, Object> infrastructureSnapshot) {
        if (infrastructureSnapshot == null) {
            return Map.of();
        }
        Object vendorsObj = infrastructureSnapshot.get("vendors");
        if (!(vendorsObj instanceof Map<?, ?> vendors)) {
            return Map.of();
        }
        Object z = vendors.get("ZERODHA");
        if (z instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    public static LiveMarketPathAssessment assessZerodhaPlatform(Map<String, Object> infrastructureSnapshot, Instant now) {
        return assessZerodhaVendor(zerodhaVendorRow(infrastructureSnapshot), now);
    }

    public static LiveMarketPathAssessment assessZerodhaVendor(Map<String, Object> z, Instant now) {
        Instant at = now != null ? now : Instant.now();
        if (z == null || z.isEmpty()) {
            return new LiveMarketPathAssessment(false, "OFFLINE", "No Zerodha platform feed row — connect admin OAuth.", at);
        }
        boolean configured = Boolean.TRUE.equals(z.get("configured"));
        if (!configured) {
            String d = String.valueOf(z.getOrDefault("detail", "Platform session exists but has no OAuth token."));
            return new LiveMarketPathAssessment(false, "OFFLINE", d, at);
        }
        if (Boolean.TRUE.equals(z.get("ingestionPaused"))) {
            return new LiveMarketPathAssessment(false, "PAUSED", "Platform ingestion is paused by operator (ingestionPaused).", at);
        }
        String conn = String.valueOf(z.getOrDefault("connectionState", "")).trim().toUpperCase();
        if ("AUTH_EXPIRED".equals(conn)) {
            return new LiveMarketPathAssessment(false, "AUTH_EXPIRED", "Kite access token expired — refresh platform session.", at);
        }
        if (Boolean.TRUE.equals(z.get("reconnecting"))) {
            return new LiveMarketPathAssessment(false, "RECONNECTING", "Platform websocket reconnecting.", at);
        }
        if (Boolean.TRUE.equals(z.get("operationalLivePath"))) {
            String detail = String.valueOf(z.getOrDefault("operationalLivePathDetail", "Live path nominal."));
            return new LiveMarketPathAssessment(true, "CONNECTED", detail, at);
        }
        String detail = String.valueOf(z.getOrDefault("operationalLivePathDetail", "Live path not operational."));
        String tape = "DEGRADED";
        if ("DISCONNECTED".equals(conn)) {
            tape = "OFFLINE";
        }
        return new LiveMarketPathAssessment(false, tape, detail, at);
    }
}
