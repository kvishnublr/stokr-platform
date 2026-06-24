package com.stokr.engine;

import com.stokr.external.ZerodhaCandleService;
import com.stokr.external.ZerodhaTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/zerodha")
@RequiredArgsConstructor
public class ZerodhaAuthController {

    private final ZerodhaTokenManager tokenManager;
    private final ZerodhaCandleService zerodhaCandleService;

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @GetMapping("/login-url")
    public ResponseEntity<Map<String, Object>> getLoginUrl() {
        String loginUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + apiKey;
        return ResponseEntity.ok(Map.of(
            "loginUrl", loginUrl,
            "instructions", "Open this URL in browser, login, and paste the request_token from the redirect URL to /api/zerodha/authenticate"
        ));
    }

    // Accept request_token (from Zerodha redirect URL) and exchange for access token
    @PostMapping("/authenticate")
    public ResponseEntity<Map<String, Object>> authenticate(@RequestParam String requestToken) {
        log.info("Zerodha authenticate: request_token={}", requestToken);
        try {
            boolean success = zerodhaCandleService.authenticate(requestToken);
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Zerodha authenticated successfully",
                    "authenticated", true
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Token exchange failed — request_token may be expired. Get a fresh one from /api/zerodha/login-url"
                ));
            }
        } catch (Exception e) {
            log.error("Zerodha authenticate failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                "status", "ERROR",
                "message", String.valueOf(e)
            ));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAuthStatus() {
        boolean authenticated = tokenManager.isAuthenticated();
        return ResponseEntity.ok(Map.of(
            "authenticated", authenticated,
            "message", authenticated ? "Zerodha authenticated" : "Zerodha not authenticated",
            "loginUrl", authenticated ? "" : "GET /api/zerodha/login-url"
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        tokenManager.clearAuth();
        log.info("Zerodha logout completed");
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Logged out from Zerodha"));
    }
}
