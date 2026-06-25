package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;
    private final ZerodhaLiveDataScheduler zerodhaScheduler;

    @GetMapping
    public ResponseEntity<List<BrokerAccount>> getMyBrokers() {
        return ResponseEntity.ok(brokerService.getUserBrokers(SecurityUtils.currentUserId()));
    }

    @GetMapping("/supported")
    public ResponseEntity<List<String>> getSupportedBrokers() {
        return ResponseEntity.ok(brokerService.getSupportedBrokers());
    }

    @GetMapping("/{brokerName}/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(@PathVariable String brokerName) {
        return ResponseEntity.ok(Map.of("authUrl", brokerService.getAuthUrl(brokerName)));
    }

    /** Frontend calls /connect — same as auth-url, returns the Kite login URL */
    @GetMapping("/{brokerName}/connect")
    public ResponseEntity<Map<String, String>> connect(@PathVariable String brokerName) {
        return ResponseEntity.ok(Map.of("authUrl", brokerService.getAuthUrl(brokerName.toUpperCase())));
    }

    /**
     * OAuth callback from Zerodha after login.
     * Zerodha redirects to this URL with ?request_token=XXX&status=success
     * This endpoint exchanges the token, saves it, then serves an HTML page that
     * notifies the parent window via postMessage and closes itself.
     */
    @GetMapping("/zerodha/callback")
    public void zerodhaCallback(
            @RequestParam(required = false) String request_token,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String auth,
            HttpServletResponse response) throws IOException {

        // If status != success or no token, show error page
        if (!"success".equalsIgnoreCase(status) || request_token == null || request_token.isBlank()) {
            response.setContentType("text/html");
            response.getWriter().write(oauthResultPage("zerodha", false, "Zerodha login failed or was cancelled", null));
            return;
        }

        // We don't have the user's JWT here (unauthenticated redirect).
        // Save the request_token in a short-lived server-side store keyed by a random code,
        // and let the frontend exchange it via POST /brokers/zerodha/token with the JWT.
        // Simpler: just pass it back to the frontend via postMessage — the frontend will POST it.
        response.setContentType("text/html");
        response.getWriter().write(oauthTokenPage("zerodha", request_token));
    }

    /**
     * Frontend POSTs the request_token (obtained from the callback page) along with the JWT.
     * Backend exchanges it for an access token and saves the broker account.
     */
    @PostMapping("/zerodha/token")
    public ResponseEntity<Map<String, Object>> exchangeZerodhaToken(@RequestBody Map<String, String> body) {
        String requestToken = body.get("requestToken");
        if (requestToken == null || requestToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "requestToken is required"));
        }
        try {
            Long userId = SecurityUtils.currentUserId();
            BrokerAccount account = brokerService.completeOAuth(userId, "ZERODHA", requestToken);
            log.info("Zerodha token exchanged for user {}, account {}", userId, account.getId());
            return ResponseEntity.ok(Map.of("status", "ok", "accountId", account.getId()));
        } catch (Exception e) {
            log.warn("Zerodha token exchange failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> disconnectBroker(@PathVariable Long accountId) {
        brokerService.disconnectBroker(accountId, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }

    // ---- helpers ----

    private String oauthResultPage(String broker, boolean ok, String message, String token) {
        String js = ok
            ? "window.opener && window.opener.postMessage({type:'stokr_broker_oauth',status:'ok',broker:'" + broker + "'}, '*'); window.close();"
            : "window.opener && window.opener.postMessage({type:'stokr_broker_oauth',status:'error',broker:'" + broker + "',message:'" + message.replace("'", "\\'") + "'}, '*'); window.close();";
        return "<!DOCTYPE html><html><body><script>" + js + "</script></body></html>";
    }

    private String oauthTokenPage(String broker, String requestToken) {
        // Sends the raw request_token to parent; parent will POST it to /zerodha/token with its JWT
        return "<!DOCTYPE html><html><body><script>" +
            "var rt='" + requestToken.replace("'", "\\'") + "';" +
            "if(window.opener){" +
            "  window.opener.postMessage({type:'stokr_broker_oauth',status:'token',broker:'" + broker + "',requestToken:rt},'*');" +
            "  window.close();" +
            "} else {" +
            "  localStorage.setItem('stokr_broker_oauth_result',JSON.stringify({status:'token',broker:'" + broker + "',requestToken:rt}));" +
            "  window.close();" +
            "}" +
            "</script><p>Connecting... you can close this window.</p></body></html>";
    }

    /**
     * Health check for Zerodha broker connectivity.
     * Returns status: OK | NO_ACCOUNT | TOKEN_EXPIRED
     * Frontend polls this and blocks live trading when not OK.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getBrokerHealth() {
        String healthStatus = zerodhaScheduler.getHealthStatus();
        boolean isOk = "OK".equals(healthStatus);

        String message = switch (healthStatus) {
            case "NO_ACCOUNT"    -> "No Zerodha account connected. Connect Zerodha to enable live trading.";
            case "TOKEN_EXPIRED" -> "Zerodha session expired. Click 'Reconnect' to restore live trading.";
            default              -> "Zerodha connected and live data active.";
        };

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",  healthStatus);
        resp.put("ok",      isOk);
        resp.put("message", message);
        resp.put("broker",  "ZERODHA");
        return ResponseEntity.ok(resp);
    }
}
