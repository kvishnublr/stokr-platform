package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motilal Oswal (MOFSL) OpenAPI adapter. TOTP-based auth.
 * Aligned with MOFSL OpenAPI docs (v7 login, v2 placeorder, v4 positions, v5 orderbook).
 *
 * IMPORTANT: symboltoken must be a numeric exchange scrip code, not a text trading symbol.
 * The caller (e.g. OptionArbAutoExecService) must resolve the trading symbol to a numeric
 * scrip token before building the BrokerOrderRequest. If symboltoken is passed as a text
 * string, MOFSL will reject the order.
 */
@Slf4j
@Component
public class MotilalOswalAdapter implements BrokerAdapter {

    private static final String MOFSL_BASE = "https://openapi.motilaloswal.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${broker.mofsl.api-key:}")
    private String apiKey;

    @Value("${broker.mofsl.api-secret:}")
    private String apiSecret;

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
        String hashedPassword = sha256(password + apiKey);
        log.info("MOFSL: logging in with TOTP for clientCode={}", clientCode);

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("userid", clientCode);
        loginBody.put("password", hashedPassword);
        loginBody.put("2FA", otp);
        loginBody.put("totp", otp);

        try {
            String respJson = mofslPost("/rest/login/v7/authdirectapi", loginBody, null);
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

    private static String mapExchange(String exchange) {
        if (exchange == null) return "NSEFO";
        return switch (exchange.toUpperCase()) {
            case "NFO", "NSEFO" -> "NSEFO";
            case "NSE" -> "NSE";
            case "BSE" -> "BSE";
            case "MCX" -> "MCX";
            case "NSECD", "CDS" -> "NSECD";
            case "BSEFO", "BFO" -> "BSEFO";
            default -> exchange;
        };
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("MOFSL: placing order {} {} {} qty={}", request.side(), request.symbol(), request.orderType(), request.quantity());
        String token = ensureToken(accessToken);

        String mofslExchange = mapExchange(request.exchange());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exchange", mofslExchange);
        body.put("symboltoken", request.symbol());
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
            String respJson = mofslPost("/rest/trans/v2/placeorder", body, token);
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
            String respJson = mofslPost("/rest/book/v4/getposition", Map.of(), token);
            JsonNode root = MAPPER.readTree(respJson);
            JsonNode positions = root.path("data");
            List<BrokerPosition> result = new ArrayList<>();
            if (positions.isArray()) {
                for (JsonNode p : positions) {
                    int buyQty = p.path("buyquantity").asInt(0);
                    int sellQty = p.path("sellquantity").asInt(0);
                    int qty = buyQty - sellQty;
                    if (qty == 0) continue;

                    BigDecimal buyAmount = new BigDecimal(p.path("buyamount").asText("0"));
                    BigDecimal sellAmount = new BigDecimal(p.path("sellamount").asText("0"));
                    BigDecimal avgPrice = qty > 0
                            ? (buyQty > 0 ? buyAmount.divide(BigDecimal.valueOf(buyQty), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                            : (sellQty > 0 ? sellAmount.divide(BigDecimal.valueOf(sellQty), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                    BigDecimal ltp = new BigDecimal(p.path("LTP").asText(p.path("ltp").asText("0")));
                    BigDecimal mtm = new BigDecimal(p.path("marktomarket").asText("0"));
                    BigDecimal booked = new BigDecimal(p.path("bookedprofitloss").asText("0"));

                    result.add(new BrokerPosition(
                            p.path("symbol").asText(""),
                            p.path("exchange").asText("NSEFO"),
                            qty,
                            avgPrice,
                            ltp,
                            mtm,
                            booked,
                            p.path("productname").asText(p.path("producttype").asText("NORMAL"))
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
            String respJson = mofslPost("/rest/report/v3/getreportmarginsummary", Map.of(), token);
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("status").asText("");
            if ("SUCCESS".equalsIgnoreCase(status)) {
                JsonNode data = root.path("data");
                double available = data.path("cashavailable").asDouble(
                        data.path("CashAvailable").asDouble(0));
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
            String respJson = mofslPost("/rest/book/v5/getorderbook", Map.of(), token);
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
        if (apiSecret != null && !apiSecret.isBlank()) {
            spec = spec.header("apisecretkey", apiSecret);
        }
        if (token != null && !token.isBlank()) {
            spec = spec.header("Authorization", token);
            spec = spec.header("accesstoken", token);
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
