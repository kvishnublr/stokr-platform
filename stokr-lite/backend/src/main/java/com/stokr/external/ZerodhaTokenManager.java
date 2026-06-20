package com.stokr.external;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ZerodhaTokenManager {

    @Value("${zerodha.api.key:}")
    private String zerodhaApiKey;

    @Value("${zerodha.api.secret:}")
    private String zerodhaApiSecret;

    @Data
    @AllArgsConstructor
    public static class ZerodhaAuth {
        private String accessToken;
        private String refreshToken;
        private Instant expiresAt;
        private boolean valid;
    }

    private ZerodhaAuth currentAuth = new ZerodhaAuth(null, null, null, false);

    public ZerodhaAuth getCurrentAuth() {
        if (currentAuth.valid && currentAuth.expiresAt != null && Instant.now().isBefore(currentAuth.expiresAt)) {
            return currentAuth;
        }
        log.warn("Zerodha auth token expired or invalid");
        return null;
    }

    public void setAuth(String accessToken, String refreshToken, int expiresIn) {
        this.currentAuth = new ZerodhaAuth(
            accessToken,
            refreshToken,
            Instant.now().plusSeconds(expiresIn - 60), // Refresh 60 seconds before expiry
            true
        );
        log.info("Zerodha auth token updated, expires in {} seconds", expiresIn);
    }

    public boolean refreshToken() {
        if (currentAuth.refreshToken == null) {
            log.error("No refresh token available");
            return false;
        }

        try {
            log.info("Refreshing Zerodha access token...");
            // In production, this would call Zerodha's token refresh endpoint
            // POST /api/token/refresh with refreshToken
            // For now, just log that it would refresh
            log.warn("Zerodha token refresh not implemented - requires actual API credentials");
            return false;

        } catch (Exception e) {
            log.error("Failed to refresh Zerodha token", e);
            return false;
        }
    }

    public boolean isAuthenticated() {
        return currentAuth != null && currentAuth.valid && getCurrentAuth() != null;
    }

    public void clearAuth() {
        this.currentAuth = new ZerodhaAuth(null, null, null, false);
        log.info("Zerodha auth cleared");
    }

    public Map<String, String> getAuthHeaders() {
        Map<String, String> headers = new HashMap<>();
        ZerodhaAuth auth = getCurrentAuth();
        if (auth != null) {
            headers.put("Authorization", "Bearer " + auth.getAccessToken());
            headers.put("Content-Type", "application/json");
        }
        return headers;
    }
}
