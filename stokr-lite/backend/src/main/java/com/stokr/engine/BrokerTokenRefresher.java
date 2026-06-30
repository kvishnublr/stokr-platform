package com.stokr.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.broker.BrokerService;
import com.stokr.broker.TotpUtils;
import com.stokr.risk.ErrorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Automated Zerodha token refresh — runs at 8:30 AM IST every weekday.
 *
 * Zerodha access tokens expire daily. This scheduler:
 *   1. Finds broker accounts with auto_reconnect=true and stored credentials
 *   2. Performs the full Zerodha login → TOTP → request_token → access_token flow
 *   3. Updates the token in DB and in-memory so live engine has a fresh token by 9:15 AM
 *
 * Required setup (once per user):
 *   - Go to Brokers page → Auto-reconnect settings → enter Zerodha password + TOTP secret
 *   - Enable auto-reconnect toggle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerTokenRefresher {

    private final BrokerAccountRepository brokerAccountRepository;
    private final BrokerService           brokerService;
    private final ErrorLogService         errorLogService;
    private final ObjectMapper            objectMapper;

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    private static final String KITE_LOGIN_URL  = "https://kite.zerodha.com/api/login";
    private static final String KITE_TWOFA_URL  = "https://kite.zerodha.com/api/twofa";

    /**
     * Runs at 8:30 AM IST every weekday — 45 min before market opens.
     * All accounts with auto_reconnect=true will have a fresh token by 9:15.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshExpiringTokens() {
        log.info("=== ZERODHA AUTO-RECONNECT STARTING ===");
        List<BrokerAccount> activeAccounts = brokerAccountRepository.findByBrokerNameAndStatus("ZERODHA", "ACTIVE");

        for (BrokerAccount account : activeAccounts) {
            if (!Boolean.TRUE.equals(account.getAutoReconnect())) continue;
            if (account.getClientId() == null || account.getZerodhaPassword() == null || account.getZerodhaTotpSecret() == null) {
                log.warn("Auto-reconnect enabled for account {} but credentials incomplete — skipping", account.getId());
                continue;
            }
            try {
                log.info("Auto-reconnecting Zerodha account {} (user: {})", account.getId(), account.getClientId());
                String requestToken = performZerodhaLogin(
                    account.getClientId(),
                    account.getZerodhaPassword(),
                    account.getZerodhaTotpSecret()
                );
                if (requestToken == null || requestToken.isBlank()) {
                    throw new RuntimeException("Failed to obtain request_token from Zerodha");
                }
                // Exchange request_token → access_token (same as manual OAuth flow)
                brokerService.completeOAuth(account.getUserId(), "ZERODHA", requestToken);
                // Record last successful auto-reconnect
                account.setLastAutoReconnect(Instant.now());
                brokerAccountRepository.save(account);
                log.info("=== AUTO-RECONNECT SUCCESS for account {} ===", account.getId());
            } catch (Exception e) {
                log.error("Auto-reconnect FAILED for account {}: {}", account.getId(), e.getMessage());
                errorLogService.logError(account.getUserId(), "AUTO_RECONNECT_FAILED",
                    "Zerodha account " + account.getId(), e.getMessage(), "ERROR");
            }
        }
        log.info("=== ZERODHA AUTO-RECONNECT DONE ===");
    }

    /**
     * Performs the full Zerodha internal API login flow:
     * POST /api/login → POST /api/twofa → follow redirects → extract request_token
     */
    private String performZerodhaLogin(String userId, String password, String totpSecret)
            throws IOException, InterruptedException {

        HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: Login ──────────────────────────────────────────────────────
        String loginBody = "user_id=" + urlEncode(userId) + "&password=" + urlEncode(password);
        HttpResponse<String> loginResp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(KITE_LOGIN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        JsonNode loginJson = objectMapper.readTree(loginResp.body());
        if (!"success".equalsIgnoreCase(loginJson.path("status").asText())) {
            throw new RuntimeException("Login failed: " + loginJson.path("message").asText());
        }
        String requestId = loginJson.path("data").path("request_id").asText();
        log.debug("Zerodha login step 1 OK, request_id={}", requestId);

        // ── Step 2: TOTP ───────────────────────────────────────────────────────
        String totp = TotpUtils.generate(totpSecret);
        String twoFaBody = "request_id=" + urlEncode(requestId)
            + "&twofa_value=" + totp
            + "&user_id=" + urlEncode(userId)
            + "&twofa_type=totp";
        HttpResponse<String> twoFaResp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(KITE_TWOFA_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0")
                .POST(HttpRequest.BodyPublishers.ofString(twoFaBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        // ── Step 3: Extract request_token from redirects ───────────────────────
        // After twofa, Zerodha returns a redirect_url in the JSON body or a 302 header
        // pointing to kite.zerodha.com/connect/finish?...&request_token=TOKEN
        // which then redirects to our redirect_uri?request_token=TOKEN
        String requestToken = findRequestToken(twoFaResp, http);
        log.debug("Zerodha login step 2+3 OK, request_token obtained: {}", requestToken != null);
        return requestToken;
    }

    private String findRequestToken(HttpResponse<String> startResp, HttpClient http)
            throws IOException, InterruptedException {

        // Check body for redirect_url (JSON response case)
        String redirectUrl = null;
        try {
            JsonNode json = objectMapper.readTree(startResp.body());
            if ("success".equalsIgnoreCase(json.path("status").asText())) {
                redirectUrl = json.path("data").path("redirect_url").asText(null);
                // Some variants put request_token directly in data
                String rt = json.path("data").path("request_token").asText(null);
                if (rt != null && !rt.isBlank()) return rt;
            } else {
                throw new RuntimeException("2FA failed: " + json.path("message").asText());
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("2FA failed")) throw (RuntimeException) e;
        }

        // Check Location header (HTTP redirect case)
        if (redirectUrl == null || redirectUrl.isBlank()) {
            redirectUrl = startResp.headers().firstValue("location").orElse(null);
        }

        // Follow redirects until we find request_token in the URL
        int maxHops = 8;
        String cur = redirectUrl;
        while (cur != null && maxHops-- > 0) {
            String extracted = extractQueryParam(cur, "request_token");
            if (extracted != null) return extracted;

            // Don't follow our own redirect_uri — just extract from the URL
            if (cur.contains("localhost") || cur.contains("stokr")) {
                return extractQueryParam(cur, "request_token");
            }

            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(cur))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            String location = resp.headers().firstValue("location").orElse(null);
            if (location != null) {
                String rt = extractQueryParam(location, "request_token");
                if (rt != null) return rt;
                cur = location;
            } else {
                break;
            }
        }
        return null;
    }

    private static String extractQueryParam(String url, String param) {
        if (url == null) return null;
        int idx = url.indexOf(param + "=");
        if (idx < 0) return null;
        int start = idx + param.length() + 1;
        int end   = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Called from admin UI to test / trigger reconnect on demand. */
    public String triggerManualReconnect(Long accountId) {
        BrokerAccount account = brokerAccountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        try {
            String requestToken = performZerodhaLogin(
                account.getClientId(), account.getZerodhaPassword(), account.getZerodhaTotpSecret());
            if (requestToken == null) return "FAILED: could not get request_token";
            brokerService.completeOAuth(account.getUserId(), "ZERODHA", requestToken);
            account.setLastAutoReconnect(Instant.now());
            brokerAccountRepository.save(account);
            return "OK";
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
