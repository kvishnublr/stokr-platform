package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motilal Oswal (MOFSL) Investor/Trading API adapter. TOTP-based auth, same shape as
 * NaviaAdapter -- client code + password + TOTP secret, no OAuth redirect.
 *
 * IMPORTANT -- this is a best-effort implementation against MOFSL's publicly documented
 * REST API conventions (login endpoint, ApiKey/vendor headers on every call, SHA256-hashed
 * password, order placement by trading symbol + exchange). It has NOT been validated against
 * a real MOFSL account or their current API docs/Postman collection. Before trusting this for
 * a live order:
 *   1. Confirm the base URL and exact endpoint paths below against your MOFSL developer
 *      portal docs (these can change between UAT/production and API versions).
 *   2. Confirm whether MOFSL expects a plain trading-symbol string for `tradingsymbol`
 *      (like Navia/Zerodha in this codebase) or a numeric scrip/symbol token instead
 *      (like several other Indian broker APIs) -- if it's token-based, placeOrder below
 *      will need a symbol->token resolution step added before it can safely place NFO
     *  option orders.
 *   3. Test getAvailableMargin() first (read-only, low risk) before placeOrder().
 */
@Slf4j
@Component
public class MotilalOswalAdapter implements BrokerAdapter {

    private static final String MOFSL_BASE = "https://openapi.motilaloswal.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${broker.mofsl.api-key:}")
    private String apiKey;

    private final RestClient http;
    private final BrokerAccountRepository repository;

    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    public MotilalOswalAdapter(RestClient.Builder restClientBuilder, BrokerAccountRepository repository) {
        this.http = restClientBuilder.build();
        this.repository = repository;
    }

    private record CachedSession(String token, String clientCode, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    @Override
    public String getBrokerName() { return "MOTILALOSWAL"; }

    @Override
    public String getAuthUrl() {
        throw new UnsupportedOperationException("Motilal Oswal uses TOTP-based auth, not OAuth.");
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        throw new UnsupportedOperationException("Motilal Oswal uses TOTP-based auth, not OAuth.");
    }

    public BrokerAccount connectWithTotp(Long userId, String clientCode, String password, String totpSecret) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MOFSL API key is not configured. Set BROKER_MOFSL_API_KEY environment variable.");
        }
        BrokerAccount account = repository.findByUserIdAndBrokerNameAndStatus(userId, "MOTILALOSWAL", "ACTIVE")
                .stream().findFirst().orElse(null);
        if (account == null) {
            account = repository.findByUserIdAndBrokerName(userId, "MOTILALOSWAL")
                    .stream().findFirst().orElse(null);
            if (account != null) account.setStatus("ACTIVE");
        }
        if (account == null) {
            account = BrokerAccount.builder()
                    .userId(userId)
                    .brokerName("MOTILALOSWAL")
                    .status("ACTIVE")
                    .build();
        }
        account.setClientId(clientCode);
        account.setMofslPassword(password);
        account.setMofslTotpSecret(totpSecret);
        account.setTokenExpiry(java.time.Instant.now().plusSeconds(365L * 24 * 3600));
        BrokerAccount saved = repository.save(account);
        try {
            String token = login(saved);
            saved.setAccessToken(token);
            repository.save(saved);
            log.info("MOFSL connected with TOTP for user {}, clientCode={}", userId, clientCode);
        } catch (Exception e) {
            log.warn("MOFSL initial login failed (credentials saved anyway): {}", e.getMessage());
        }
        return saved;
    }

    private String login(BrokerAccount account) {
        String clientCode = account.getClientId();
        String password = account.getMofslPassword();
        String totpSecret = account.getMofslTotpSecret();

        if (clientCode == null || clientCode.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("MOFSL client code and password are required.");
        }
        if (totpSecret == null || totpSecret.isBlank()) {
            throw new IllegalStateException("MOFSL TOTP secret is required.");
        }

        CachedSession cached = sessionCache.get(account.getId());
        if (cached != null && !cached.isExpired()) {
            log.debug("MOFSL: reusing cached session for account {}", account.getId());
            return cached.token;
        }

        String otp = TotpUtils.generate(totpSecret);
        log.info("MOFSL: logging in with TOTP for clientCode={}", clientCode);

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("userid", clientCode);
        loginBody.put("password", sha256(password));
        loginBody.put("2FA", otp);
        loginBody.put("totp", otp);

        try {
            String respJson = mofslPost("/rest/login/v3/authdirectapi", loginBody, null);
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("status").asText("");
            if (!"SUCCESS".equalsIgnoreCase(status)) {
                throw new RuntimeException("MOFSL login failed: " + root.path("message").asText(respJson));
            }
            String token = root.path("AuthToken").asText(null);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("MOFSL login returned no AuthToken");
            }
            sessionCache.put(account.getId(), new CachedSession(token, clientCode, System.currentTimeMillis() + 8 * 60 * 60 * 1000));
            log.info("MOFSL: login successful for clientCode={}", clientCode);
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MOFSL login error: " + e.getMessage(), e);
        }
    }

    private String ensureToken(String accessToken) {
        // accessToken passed in is the stored value from broker_accounts.access_token, which
        // may be stale (MOFSL sessions expire); re-login using the account's saved credentials
        // whenever we can resolve which account this token belongs to.
        for (var entry : sessionCache.entrySet()) {
            if (entry.getValue().token.equals(accessToken) && !entry.getValue().isExpired()) {
                return accessToken;
            }
        }
        List<BrokerAccount> accounts = repository.findByBrokerNameAndStatus("MOTILALOSWAL", "ACTIVE");
        if (!accounts.isEmpty()) {
            try { return login(accounts.get(0)); } catch (Exception e) { log.warn("MOFSL re-login failed: {}", e.getMessage()); }
        }
        return accessToken;
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("MOFSL: placing order {} {} {} qty={}", request.side(), request.symbol(), request.orderType(), request.quantity());
        String token = ensureToken(accessToken);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exchange", request.exchange() != null ? request.exchange() : "NFO");
        body.put("symboltoken", request.symbol()); // see class javadoc -- may need numeric token instead
        body.put("tradingsymbol", request.symbol());
        body.put("buyorsell", request.side().name());
        body.put("ordertype", request.price() != null && request.price() > 0 ? "LIMIT" : "MARKET");
        body.put("producttype", request.productType() != null ? request.productType() : "NORMAL");
        body.put("orderduration", "DAY");
        body.put("price", request.price() != null ? request.price() : 0.0);
        body.put("triggerprice", 0.0);
        body.put("quantityinlot", request.quantity());
        body.put("disclosedquantity", 0);
        body.put("amoorder", "N");
        body.put("algoid", "");
        body.put("goodtilldate", "");
        body.put("tag", "STOKR");

        try {
            String respJson = mofslPost("/rest/trans/v1/placeorder", body, token);
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("status").asText("");
            String message = root.path("message").asText("");
            if ("SUCCESS".equalsIgnoreCase(status)) {
                String orderId = root.path("uniqueorderid").asText(root.path("orderid").asText(null));
                log.info("MOFSL order placed: {} -> {} (message={})", request.symbol(), orderId, message);
                return new BrokerOrderResponse(orderId, "OPEN", message);
            }
            log.warn("MOFSL order REJECTED: symbol={} side={} qty={} status={} message={}",
                    request.symbol(), request.side(), request.quantity(), status, message);
            return new BrokerOrderResponse(null, "REJECTED", message);
        } catch (Exception e) {
            log.error("MOFSL placeOrder failed for {}: {}", request.symbol(), e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("MOFSL: cancelling order {}", orderId);
        String token = ensureToken(accessToken);
        Map<String, Object> body = Map.of("uniqueorderid", orderId);
        try {
            mofslPost("/rest/trans/v1/cancelorder", body, token);
        } catch (Exception e) {
            log.warn("MOFSL cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("MOFSL: fetching positions");
        String token = ensureToken(accessToken);
        try {
            String respJson = mofslPost("/rest/book/v1/getposition", Map.of(), token);
            JsonNode root = MAPPER.readTree(respJson);
            JsonNode positions = root.path("data");
            List<BrokerPosition> result = new ArrayList<>();
            if (positions.isArray()) {
                for (JsonNode p : positions) {
                    int qty = p.path("buyquantity").asInt(0) - p.path("sellquantity").asInt(0);
                    if (qty == 0) continue;
                    result.add(new BrokerPosition(
                            p.path("symbol").asText(""),
                            p.path("exchange").asText("NFO"),
                            qty,
                            new BigDecimal(p.path("buyavgprice").asText("0")),
                            new BigDecimal(p.path("ltp").asText("0")),
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            p.path("producttype").asText("NORMAL")
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("MOFSL getPositions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("MOFSL: fetching available margin");
        String token = ensureToken(accessToken);
        try {
            String respJson = mofslPost("/rest/report/v1/getreportmargin", Map.of(), token);
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("status").asText("");
            if ("SUCCESS".equalsIgnoreCase(status)) {
                JsonNode data = root.path("data");
                double available = data.path("cashavailable").asDouble(0);
                log.info("MOFSL: available margin={}", available);
                return BigDecimal.valueOf(available);
            }
            log.warn("MOFSL margin fetch failed: {}", root.path("message").asText());
        } catch (Exception e) {
            log.warn("MOFSL getAvailableMargin failed: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        String token = ensureToken(accessToken);
        try {
            String respJson = mofslPost("/rest/book/v1/getorderbook", Map.of(), token);
            JsonNode root = MAPPER.readTree(respJson);
            JsonNode orders = root.path("data");
            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    String id = o.path("uniqueorderid").asText(o.path("orderid").asText(""));
                    if (orderId.equals(id)) {
                        return o.path("orderstatus").asText("UNKNOWN");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MOFSL getOrderStatus {} failed: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }

    private String mofslPost(String path, Map<String, Object> body, String token) throws Exception {
        String bodyJson = MAPPER.writeValueAsString(body);
        var spec = http.post()
                .uri(MOFSL_BASE + path)
                .header("Content-Type", "application/json")
                .header("ApiKey", apiKey != null ? apiKey : "")
                .header("SourceId", "WEB")
                .header("vendorinfo", "STOKR")
                .header("ClientLocalIp", "127.0.0.1")
                .header("ClientPublicIp", "127.0.0.1")
                .header("MacAddress", "00:00:00:00:00:00");
        if (token != null && !token.isBlank()) {
            spec = spec.header("Authorization", token);
        }
        return spec.body(bodyJson).retrieve().body(String.class);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed", e);
        }
    }
}
