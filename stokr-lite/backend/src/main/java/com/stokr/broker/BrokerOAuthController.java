package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerOAuthController {

    private final BrokerService brokerService;

    /**
     * Initiate broker connection - stores userId in cookie before redirecting to broker.
     * This endpoint requires authentication.
     */
    @GetMapping("/{brokerName}/connect")
    public ResponseEntity<Map<String, String>> connect(
            @PathVariable String brokerName,
            HttpServletResponse response) {
        Long userId = SecurityUtils.currentUserId();
        // Store userId in cookie so callback can retrieve it
        Cookie cookie = new Cookie("stokr_broker_user", userId.toString());
        cookie.setMaxAge(300); // 5 minutes
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        String authUrl = brokerService.getAuthUrl(brokerName);
        return ResponseEntity.ok(Map.of("authUrl", authUrl));
    }

    /**
     * OAuth callback - called by broker after user authorizes.
     * This endpoint is publicly accessible (configured in SecurityConfig).
     * Retrieves userId from cookie set during /connect.
     */
    @GetMapping("/{brokerName}/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @PathVariable String brokerName,
            @RequestParam("request_token") String requestToken,
            HttpServletRequest request) {

        // Retrieve userId from cookie
        Long userId = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("stokr_broker_user".equals(cookie.getName())) {
                    userId = Long.parseLong(cookie.getValue());
                    break;
                }
            }
        }

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session expired. Please try connecting again."
            ));
        }

        try {
            BrokerAccount account = brokerService.completeOAuth(userId, brokerName, requestToken);
            return ResponseEntity.ok(Map.of(
                    "status", "connected",
                    "brokerAccountId", account.getId(),
                    "brokerName", account.getBrokerName()
            ));
        } catch (Exception e) {
            log.error("OAuth callback failed for broker {} user {}", brokerName, userId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Broker connection failed: " + e.getMessage()
            ));
        }
    }
}
