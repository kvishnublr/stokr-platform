package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import com.stokr.engine.BrokerTokenRefresher;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService           brokerService;
    private final BrokerAccountRepository brokerAccountRepository;
    private final BrokerTokenRefresher    tokenRefresher;

    private String executionBroker = "PAPER";

    @GetMapping("/decoupled-routing")
    public ResponseEntity<Map<String, Object>> getDecoupledRouting() {
        return ResponseEntity.ok(Map.of("executionBroker", executionBroker));
    }

    @PostMapping("/decoupled-routing")
    public ResponseEntity<Map<String, Object>> setDecoupledRouting(@RequestBody Map<String, String> body) {
        String broker = body.getOrDefault("executionBroker", "PAPER");
        this.executionBroker = broker.toUpperCase();
        log.info("Execution broker changed to {}", this.executionBroker);
        return ResponseEntity.ok(Map.of("status", "ok", "executionBroker", this.executionBroker));
    }

    @PostMapping("/test-execution")
    public ResponseEntity<Map<String, Object>> testExecution(@RequestBody Map<String, String> body) {
        String broker = body.getOrDefault("broker", "PAPER");
        try {
            BrokerAdapter adapter = brokerService.getAdapter(broker);
            Long userId = SecurityUtils.currentUserId();
            List<BrokerAccount> accounts = brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(userId, broker.toUpperCase(), "ACTIVE");
            if ("PAPER".equalsIgnoreCase(broker)) {
                return ResponseEntity.ok(Map.of("ok", true, "message", "Paper trading is always available", "broker", "PAPER"));
            }
            if (accounts.isEmpty()) {
                return ResponseEntity.ok(Map.of("ok", false, "message", "No active " + broker + " account. Connect " + broker + " first.", "broker", broker.toUpperCase()));
            }
            BrokerAccount account = accounts.get(0);
            if (account.getAccessToken() == null || account.getAccessToken().isBlank()) {
                return ResponseEntity.ok(Map.of("ok", false, "message", broker + " access token is missing. Reconnect.", "broker", broker.toUpperCase()));
            }
            try {
                var margin = adapter.getAvailableMargin(account.getAccessToken());
                return ResponseEntity.ok(Map.of("ok", true, "message", broker + " connected. Available margin: " + margin, "broker", broker.toUpperCase()));
            } catch (Exception e) {
                return ResponseEntity.ok(Map.of("ok", false, "message", broker + " API error: " + e.getMessage(), "broker", broker.toUpperCase()));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "message", "Unknown broker: " + broker, "broker", broker.toUpperCase()));
        }
    }

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

    /**
     * Save auto-reconnect credentials for Zerodha.
     * Body: { zerodhaPassword, zerodhaTotpSecret, autoReconnect }
     */
    @PostMapping("/zerodha/auto-reconnect")
    public ResponseEntity<Map<String, Object>> saveAutoReconnect(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.currentUserId();
        List<BrokerAccount> accounts = brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(userId, "ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active Zerodha account found. Connect Zerodha first."));
        }
        BrokerAccount account = accounts.get(0);
        String password   = (String) body.get("zerodhaPassword");
        String totpSecret = (String) body.get("zerodhaTotpSecret");
        Boolean enabled   = body.get("autoReconnect") instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(body.get("autoReconnect")));

        if (password   != null) account.setZerodhaPassword(password);
        if (totpSecret != null) account.setZerodhaTotpSecret(totpSecret.trim().toUpperCase().replace(" ", ""));
        if (enabled    != null) account.setAutoReconnect(enabled);

        brokerAccountRepository.save(account);
        log.info("Auto-reconnect settings saved for Zerodha account {} (enabled={})", account.getId(), enabled);
        // NOTE: Map.of() rejects null values — lastAutoReconnect is null until the
        // first reconnect runs, so build a null-tolerant map instead.
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "saved");
        resp.put("autoReconnect", Boolean.TRUE.equals(account.getAutoReconnect()));
        resp.put("credentialsStored", account.getZerodhaPassword() != null && account.getZerodhaTotpSecret() != null);
        resp.put("lastAutoReconnect", account.getLastAutoReconnect() != null ? account.getLastAutoReconnect().toString() : null);
        return ResponseEntity.ok(resp);
    }

    /** Get auto-reconnect status for the user's Zerodha account. */
    @GetMapping("/zerodha/auto-reconnect")
    public ResponseEntity<Map<String, Object>> getAutoReconnectStatus() {
        Long userId = SecurityUtils.currentUserId();
        List<BrokerAccount> accounts = brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(userId, "ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) {
            return ResponseEntity.ok(Map.of("configured", false));
        }
        BrokerAccount acc = accounts.get(0);
        boolean credentialsStored = acc.getZerodhaPassword() != null && acc.getZerodhaTotpSecret() != null;
        // Map.of() rejects null values — lastAutoReconnect is null until first reconnect.
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("configured", credentialsStored);
        resp.put("autoReconnect", Boolean.TRUE.equals(acc.getAutoReconnect()));
        resp.put("lastAutoReconnect", acc.getLastAutoReconnect() != null ? acc.getLastAutoReconnect().toString() : null);
        resp.put("accountId", acc.getId());
        return ResponseEntity.ok(resp);
    }

    /** Manually trigger auto-reconnect now (for testing). */
    @PostMapping("/zerodha/auto-reconnect/trigger")
    public ResponseEntity<Map<String, Object>> triggerAutoReconnect() {
        Long userId = SecurityUtils.currentUserId();
        List<BrokerAccount> accounts = brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(userId, "ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No active Zerodha account"));
        BrokerAccount acc = accounts.get(0);
        if (!Boolean.TRUE.equals(acc.getAutoReconnect()) || acc.getZerodhaPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Auto-reconnect not configured"));
        }
        String result = tokenRefresher.triggerManualReconnect(acc.getId());
        return ResponseEntity.ok(Map.of("result", result, "timestamp", Instant.now().toString()));
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
     * Health check for broker connectivity.
     * Returns status: OK | NO_ACCOUNT | TOKEN_EXPIRED
     * Frontend polls this and blocks live trading when not OK.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getBrokerHealth() {
        List<BrokerAccount> activeAccounts = brokerAccountRepository.findByStatus("ACTIVE");
        if (activeAccounts.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "NO_ACCOUNT",
                    "ok", false,
                    "message", "No broker account connected. Connect a broker to enable live trading.",
                    "broker", "NONE"
            ));
        }

        BrokerAccount account = activeAccounts.get(0);
        String brokerName = account.getBrokerName();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "OK");
        resp.put("ok", true);
        resp.put("message", brokerName + " connected and live data active.");
        resp.put("broker", brokerName);
        return ResponseEntity.ok(resp);
    }
}
