package com.stokr.engine;

import com.stokr.external.ZerodhaTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/zerodha")
@RequiredArgsConstructor
public class ZerodhaAuthController {

    private final ZerodhaTokenManager tokenManager;

    @PostMapping("/request-auth")
    public ResponseEntity<Map<String, Object>> requestAuth() {
        log.info("Zerodha authentication requested");

        // In production, this would return a login URL for the user to authenticate
        // The user would be redirected to Zerodha's login, which returns a request_token
        // The client would then call /callback with that request_token

        String zerodhaLoginUrl = "https://kite.zerodha.com/connect/login?api_key=YOUR_API_KEY&redirect_url=https://your-domain/api/zerodha/callback";

        return ResponseEntity.ok(Map.of(
            "status", "AUTH_REQUIRED",
            "message", "Please authenticate with Zerodha",
            "loginUrl", zerodhaLoginUrl,
            "instructions", "Click the link above to authenticate. You will be redirected back with a request token."
        ));
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(@RequestParam String requestToken) {
        log.info("Zerodha callback received with request token");

        try {
            // Exchange request token for access token
            // In production: call Zerodha token generation endpoint
            boolean success = false; // zerodhaCandleService.authenticate(requestToken, apiSecret);

            if (success) {
                return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Zerodha authentication successful",
                    "authenticated", true
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to authenticate with Zerodha"
                ));
            }

        } catch (Exception e) {
            log.error("Zerodha authentication callback failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                "status", "ERROR",
                "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, Object>> refreshToken() {
        log.info("Zerodha token refresh requested");

        try {
            boolean success = tokenManager.refreshToken();

            if (success) {
                return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Token refreshed successfully",
                    "authenticated", true
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to refresh token - please re-authenticate"
                ));
            }

        } catch (Exception e) {
            log.error("Token refresh failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                "status", "ERROR",
                "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAuthStatus() {
        boolean authenticated = tokenManager.isAuthenticated();

        return ResponseEntity.ok(Map.of(
            "authenticated", authenticated,
            "message", authenticated ? "Zerodha authenticated" : "Zerodha not authenticated",
            "action", authenticated ? "ready_for_backtest" : "request_auth_required"
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        tokenManager.clearAuth();
        log.info("Zerodha logout completed");

        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "message", "Logged out from Zerodha"
        ));
    }
}
