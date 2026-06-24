package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BrokerOAuthController {

    private final BrokerService brokerService;

    @Value("${stokr.ui.base-url:http://localhost:5173}")
    private String uiBaseUrl;

    /**
     * Initiate broker connection - stores userId in cookie before redirecting to broker.
     * This endpoint requires authentication.
     */
    @GetMapping({"/api/brokers/{brokerName}/connect", "/api/broker/{brokerName}/connect"})
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
     *
     * Zerodha sends: request_token, status, action
     * Dhan sends: code (auth code), state
     * Fyers sends: auth_code, state
     *
     * On success or failure, redirects to frontend /brokers/callback page
     * with query params indicating the result.
     */
    // Handles both /api/brokers/zerodha/callback AND /api/broker/zerodha/callback
    // (Zerodha developer console was registered with the singular form)
    @GetMapping({"/api/brokers/{brokerName}/callback", "/api/broker/{brokerName}/callback"})
    public RedirectView handleCallback(
            @PathVariable String brokerName,
            @RequestParam(value = "request_token", required = false) String requestToken,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "code", required = false) String authCode,
            @RequestParam(value = "auth_code", required = false) String fyersAuthCode,
            @RequestParam(value = "state", required = false) String state,
            HttpServletRequest request) {

        String lowerBroker = brokerName.toLowerCase();

        // Zerodha sends status=error when user denies
        if ("error".equalsIgnoreCase(status)) {
            log.warn("broker.callback.user_denied broker={}", brokerName);
            return redirectWithError(lowerBroker, "user_denied", "You cancelled the broker login.");
        }

        // Determine the token value based on broker
        String token = resolveToken(brokerName, requestToken, authCode, fyersAuthCode);

        if (token == null || token.isBlank()) {
            log.warn("broker.callback.missing_token broker={} requestToken={} authCode={} fyersAuthCode={} status={} action={}",
                    brokerName, requestToken != null, authCode != null, fyersAuthCode != null, status, action);
            return redirectWithError(lowerBroker, "missing_params", "Required authentication parameters were not received.");
        }

        // Retrieve userId from cookie
        Long userId = extractUserIdFromCookie(request);
        if (userId == null) {
            log.warn("broker.callback.no_user_cookie broker={}", brokerName);
            return redirectWithError(lowerBroker, "session_expired", "Session expired. Please try connecting again.");
        }

        try {
            BrokerAccount account = brokerService.completeOAuth(userId, brokerName, token);
            log.info("broker.callback.success broker={} userId={}", brokerName, userId);
            return redirectWithSuccess(lowerBroker, account.getId(), account.getBrokerName());
        } catch (UnsupportedOperationException e) {
            log.error("broker.callback.not_implemented broker={} msg={}", brokerName, e.getMessage());
            return redirectWithError(lowerBroker, "not_supported", "Broker connection is not fully implemented yet. Coming soon!");
        } catch (Exception e) {
            log.error("broker.callback.failed broker={} userId={}", brokerName, userId, e);
            String msg = e.getMessage() != null ? e.getMessage() : "Broker connection failed";
            return redirectWithError(lowerBroker, "connection_failed", msg);
        }
    }

    private String resolveToken(String brokerName, String requestToken, String authCode, String fyersAuthCode) {
        return switch (brokerName.toUpperCase()) {
            case "ZERODHA" -> requestToken;
            case "DHAN" -> authCode;
            case "FYERS" -> fyersAuthCode != null ? fyersAuthCode : authCode;
            default -> requestToken != null ? requestToken : authCode;
        };
    }

    private Long extractUserIdFromCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("stokr_broker_user".equals(cookie.getName())) {
                    try {
                        return Long.parseLong(cookie.getValue());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private RedirectView redirectWithSuccess(String brokerName, Long accountId, String brokerDisplayName) {
        String url = uiBaseUrl + "/brokers/callback?broker=" + brokerName + "&status=ok&accountId=" + accountId;
        return new RedirectView(url);
    }

    private RedirectView redirectWithError(String brokerName, String reason, String message) {
        String url = uiBaseUrl + "/brokers/callback?broker=" + brokerName + "&status=error&reason=" + reason + "&message=" + urlEncode(message);
        return new RedirectView(url);
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
