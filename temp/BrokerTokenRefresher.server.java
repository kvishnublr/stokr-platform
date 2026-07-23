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
    private final BrokerService brokerService;
    private final ErrorLogService errorLogService;
    private final ObjectMapper objectMapper;

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    private static final String KITE_LOGIN_URL = "https://kite.zerodha.com/api/login";
    private static final String KITE_TWOFA_URL = "https://kite.zerodha.com/api/twofa";

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
                brokerService.completeOAuth(account.getUserId(), "ZERODHA", requestToken);
                BrokerAccount fresh = brokerAccountRepository.findById(account.getId()).orElse(account);
                fresh.setLastAutoReconnect(Instant.now());
                brokerAccountRepository.save(fresh);
                log.info("=== AUTO-RECONNECT SUCCESS for account {} ===", account.getId());
            } catch (Exception e) {
                log.error("Auto-reconnect FAILED for account {}: {}", account.getId(), e.getMessage());
                errorLogService.logError(account.getUserId(), "AUTO_RECONNECT_FAILED",
                    "Zerodha account " + account.getId(), e.getMessage(), "ERROR");
            }
        }
        log.info("=== ZERODHA AUTO-RECONNECT DONE ===");
    }

    private String performZerodhaLogin(String userId, String password, String totpSecret)
            throws IOException, InterruptedException {

        HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

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

        JsonNode twoFaJson = objectMapper.readTree(twoFaResp.body());
        if (!"success".equalsIgnoreCase(twoFaJson.path("status").asText())) {
            throw new RuntimeException("2FA failed: " + twoFaJson.path("message").asText());
        }
        log.debug("Zerodha login step 2 (TOTP) OK");

        if (zerodhaApiKey == null || zerodhaApiKey.isBlank()) {
            throw new RuntimeException("ZERODHA_API_KEY not configured on server");
        }
        String connectUrl = "https://kite.zerodha.com/connect/login?api_key="
            + urlEncode(zerodhaApiKey) + "&v=3";
        String requestToken = followUntilRequestToken(connectUrl, http);
        log.debug("Zerodha login step 3 (connect) done, request_token obtained: {}", requestToken != null);
        return requestToken;
    }

    private String followUntilRequestToken(String startUrl, HttpClient http)
            throws IOException, InterruptedException {
        String cur = startUrl;
        int maxHops = 10;
        while (cur != null && maxHops-- > 0) {
            String rt = extractQueryParam(cur, "request_token");
            if (rt != null && !rt.isBlank()) return rt;

            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(cur))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            String location = resp.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) break;

            if (location.startsWith("/")) {
                URI base = URI.create(cur);
                location = base.getScheme() + "://" + base.getHost() + location;
            }
            String rtLoc = extractQueryParam(location, "request_token");
            if (rtLoc != null && !rtLoc.isBlank()) return rtLoc;
            cur = location;
        }
        return null;
    }

    private static String extractQueryParam(String url, String param) {
        if (url == null) return null;
        int idx = url.indexOf(param + "=");
        if (idx < 0) return null;
        int start = idx + param.length() + 1;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public String triggerManualReconnectForUser(Long userId) {
        List<BrokerAccount> accounts =
            brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(userId, "ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) return "FAILED: no active Zerodha account";
        return triggerManualReconnect(accounts.get(0).getId());
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
            BrokerAccount fresh = brokerAccountRepository.findById(account.getId()).orElse(account);
            fresh.setLastAutoReconnect(Instant.now());
            brokerAccountRepository.save(fresh);
            return "OK";
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
