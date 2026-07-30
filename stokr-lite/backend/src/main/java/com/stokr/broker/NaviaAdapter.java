package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class NaviaAdapter implements BrokerAdapter {

    private static final String NAVIA_BASE = "https://naviaapt.navia.co.in:9003/?Activity=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;
    private final BrokerAccountRepository repository;

    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    public NaviaAdapter(RestClient.Builder restClientBuilder, BrokerAccountRepository repository) {
        this.http = restClientBuilder.build();
        this.repository = repository;
    }

    private record CachedSession(String token, String uid, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    @Override
    public String getBrokerName() { return "NAVIA"; }

    @Override
    public String getAuthUrl() {
        throw new UnsupportedOperationException("Navia uses TOTP-based auth, not OAuth.");
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        throw new UnsupportedOperationException("Navia uses TOTP-based auth, not OAuth.");
    }

    public String loginWithTotp(Long userId) {
        BrokerAccount account = repository.findByUserIdAndBrokerNameAndStatus(userId, "NAVIA", "ACTIVE")
                .stream().findFirst().orElse(null);
        if (account == null) {
            throw new IllegalStateException("No active Navia account. Connect Navia first.");
        }
        return loginWithTotp(account);
    }

    public String loginWithTotp(BrokerAccount account) {
        String uid = account.getClientId();
        String password = account.getNaviaApiSecret();
        String totpSecret = account.getNaviaApiKey();

        if (uid == null || uid.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Navia UID and password are required.");
        }
        if (totpSecret == null || totpSecret.isBlank()) {
            throw new IllegalStateException("Navia TOTP secret is required.");
        }

        CachedSession cached = sessionCache.get(account.getId());
        if (cached != null && !cached.isExpired()) {
            log.debug("Navia: reusing cached session for account {}", account.getId());
            return cached.token;
        }

        String otp = generateTotp(totpSecret);
        log.info("Navia: logging in with TOTP for uid={}", uid);

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("site", "rocket");
        loginBody.put("user_name", uid);
        loginBody.put("uid", uid);
        loginBody.put("password", password);
        loginBody.put("pwd", password);
        loginBody.put("otp", otp);
        loginBody.put("source", "web");

        try {
            String respJson = naviaPost("Login", loginBody, "DEFAULT", null);
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("Status").asText("");
            String message = root.path("Message").asText("");
            if (!"OK".equalsIgnoreCase(status)) {
                throw new RuntimeException("Navia login failed: " + message);
            }
            String token = root.path("ResponceDataObject").path("susertoken").asText(null);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Navia login returned no token");
            }
            sessionCache.put(account.getId(), new CachedSession(token, account.getClientId(), System.currentTimeMillis() + 5 * 60 * 1000));
            log.info("Navia: login successful for uid={}", uid);
            return account.getClientId() + ":" + token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Navia login error: " + e.getMessage(), e);
        }
    }

    private String ensureToken(Long accountId, String accessToken) {
        BrokerAccount account = repository.findById(accountId).orElse(null);
        if (account != null) {
            try { return loginWithTotp(account); } catch (Exception e) { log.warn("Navia re-login failed: {}", e.getMessage()); }
        }
        return accessToken;
    }

    private String getFreshToken() {
        List<BrokerAccount> accounts = repository.findByBrokerNameAndStatus("NAVIA", "ACTIVE");
        if (accounts.isEmpty()) throw new RuntimeException("No active Navia account");
        BrokerAccount account = accounts.get(0);
        return loginWithTotp(account);
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Navia: placing order {} {} {} qty={}", request.side(), request.symbol(), request.orderType(), request.quantity());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", extractUid(accessToken));
        body.put("actid", extractUid(accessToken));
        body.put("exch", request.exchange() != null ? request.exchange() : "NFO");
        body.put("trantype", request.side().name());
        body.put("tsym", request.symbol());
        body.put("qty", request.quantity());
        body.put("trgprc", "0");
        body.put("dscqty", 0);
        body.put("prd", request.productType() != null ? request.productType() : "MIS");
        body.put("ret", "DAY");
        body.put("ordersource", "Web");
        body.put("segment", "FNO");
        body.put("mkt_protection", "0");
        body.put("remarks", "");
        body.put("ext_remarks", "");
        body.put("cl_ord_id", "");
        body.put("algo_id", "0");

        if (request.price() != null && request.price() > 0) {
            body.put("prctyp", "LIMIT");
            body.put("prc", String.valueOf(request.price()));
        } else {
            body.put("prctyp", "MKT");
            body.put("prc", "0");
        }

        try {
            String respJson = naviaPost("PlaceOrder", body, "OrderService", extractToken(accessToken));
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("Status").asText("");
            String message = root.path("Message").asText("");
            if ("OK".equalsIgnoreCase(status)) {
                String orderId = root.path("ResponceDataObject").path("cl_ord_id").asText(null);
                log.info("Navia order placed: {} -> {}", request.symbol(), orderId);
                return new BrokerOrderResponse(orderId, "OPEN", message);
            }
            log.warn("Navia order rejected: {}", message);
            return new BrokerOrderResponse(null, "REJECTED", message);
        } catch (Exception e) {
            log.error("Navia placeOrder failed for {}: {}", request.symbol(), e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Navia: cancelling order {}", orderId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", extractUid(accessToken));
        body.put("actid", extractUid(accessToken));
        body.put("exch", "NFO");
        body.put("orderid", orderId);
        try {
            naviaPost("CancelOrder", body, "OrderService", extractToken(accessToken));
        } catch (Exception e) {
            log.warn("Navia cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Navia: fetching positions");
        try {
            Map<String, Object> body = Map.of("uid", extractUid(accessToken));
            String respJson = naviaPost("PositionBook", body, "OrderService", extractToken(accessToken));
            JsonNode root = MAPPER.readTree(respJson);
            JsonNode positions = root.path("ResponceDataObject").path("Positions");
            List<BrokerPosition> result = new ArrayList<>();
            if (positions.isArray()) {
                for (JsonNode p : positions) {
                    int qty = p.path("qty").asInt(0);
                    if (qty == 0) continue;
                    result.add(new BrokerPosition(
                            p.path("tsym").asText(""),
                            p.path("exch").asText("NFO"),
                            qty,
                            new BigDecimal(p.path("avgprc").asText("0")),
                            new BigDecimal(p.path("lastprice").asText("0")),
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            p.path("prd").asText("MIS")
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Navia getPositions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("Navia: fetching available margin via GetOrderMargin");
        try {
            String uid = resolveUid(accessToken);
            if (uid == null) {
                log.warn("Navia: could not resolve UID from token");
                return BigDecimal.ZERO;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uid", uid);
            body.put("actid", uid);
            body.put("exch", "NSE");
            body.put("trantype", "Buy");
            body.put("tsym", "RELIANCE-EQ");
            body.put("qty", 1);
            body.put("prc", "0");
            body.put("trgprc", "0");
            body.put("dscqty", 0);
            body.put("prd", "MIS");
            body.put("prctyp", "MKT");
            body.put("ret", "DAY");
            body.put("ordersource", "Web");
            body.put("segment", "CASH");
            body.put("mkt_protection", "0");
            body.put("remarks", "");
            body.put("ext_remarks", "");
            body.put("cl_ord_id", "");
            body.put("algo_id", "0");

            String respJson = naviaPost("GetOrderMargin", body, "OrderService", extractToken(accessToken));
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("Status").asText("");
            if ("OK".equalsIgnoreCase(status) || "Ok".equalsIgnoreCase(status)) {
                JsonNode rd = root.path("ResponceDataObject");
                BigDecimal availableMargin = new BigDecimal(rd.path("AvailableMargin").asText("0"));
                log.info("Navia: AvailableMargin={}", availableMargin);
                return availableMargin;
            }
            log.warn("Navia GetOrderMargin failed: {}", root.path("Message").asText());
        } catch (Exception e) {
            log.warn("Navia getAvailableMargin failed: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        try {
            Map<String, Object> body = Map.of("uid", extractUid(accessToken));
            String respJson = naviaPost("OrderBook", body, "OrderService", extractToken(accessToken));
            JsonNode root = MAPPER.readTree(respJson);
            JsonNode orders = root.path("ResponceDataObject").path("AllOrders");
            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    if (orderId.equals(o.path("cl_ord_id").asText(""))) {
                        return o.path("status").asText("UNKNOWN");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Navia getOrderStatus {} failed: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }

    public List<Map<String, Object>> getOrderBook(String accessToken) {
        try {
            Map<String, Object> body = Map.of("uid", extractUid(accessToken));
            String respJson = naviaPost("OrderBook", body, "OrderService", extractToken(accessToken));
            JsonNode root = MAPPER.readTree(respJson);
            JsonNode orders = root.path("ResponceDataObject").path("AllOrders");
            List<Map<String, Object>> result = new ArrayList<>();
            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    Map<String, Object> order = new LinkedHashMap<>();
                    order.put("orderId", o.path("cl_ord_id").asText(""));
                    order.put("symbol", o.path("tsym").asText(""));
                    order.put("exchange", o.path("exch").asText(""));
                    order.put("side", o.path("trantype").asText(""));
                    order.put("qty", o.path("qty").asInt(0));
                    order.put("price", o.path("prc").asText("0"));
                    order.put("status", o.path("OrderStatusMessage").asText(""));
                    order.put("product", o.path("prd").asText(""));
                    result.add(order);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Navia getOrderBook failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public BrokerAccount connectWithTotp(Long userId, String uid, String password, String totpSecret) {
        BrokerAccount account = repository.findByUserIdAndBrokerNameAndStatus(userId, "NAVIA", "ACTIVE")
                .stream().findFirst().orElse(null);
        if (account == null) {
            account = repository.findByUserIdAndBrokerName(userId, "NAVIA")
                    .stream().findFirst().orElse(null);
            if (account != null) account.setStatus("ACTIVE");
        }
        if (account == null) {
            account = BrokerAccount.builder()
                    .userId(userId)
                    .brokerName("NAVIA")
                    .status("ACTIVE")
                    .build();
        }
        account.setClientId(uid);
        account.setNaviaApiKey(totpSecret);
        account.setNaviaApiSecret(password);
        account.setTokenExpiry(java.time.Instant.now().plusSeconds(365L * 24 * 3600));
        BrokerAccount saved = repository.save(account);
        try {
            String token = loginWithTotp(saved);
            saved.setAccessToken(token);
            repository.save(saved);
            log.info("Navia connected with TOTP for user {}, uid={}", userId, uid);
        } catch (Exception e) {
            log.warn("Navia initial login failed (credentials saved anyway): {}", e.getMessage());
        }
        return saved;
    }

    private String naviaPost(String activity, Map<String, Object> body, String module, String token) throws Exception {
        String url = NAVIA_BASE + activity;
        String bodyJson = MAPPER.writeValueAsString(body);
        String result = doPost(url, bodyJson, module, token);
        if (result != null && result.contains("Authentication Failed")) {
            log.info("Navia: auth failed on {}, re-logging in and retrying...", activity);
            try {
                String freshToken = getFreshToken();
                result = doPost(url, bodyJson, module, extractToken(freshToken));
                if (result != null && result.contains("Authentication Failed")) {
                    log.warn("Navia: retry also auth-failed, clearing cache");
                    sessionCache.clear();
                    freshToken = getFreshToken();
                    result = doPost(url, bodyJson, module, extractToken(freshToken));
                }
            } catch (Exception e) {
                log.warn("Navia: retry login failed: {}", e.getMessage());
            }
        }
        return result;
    }

    private String doPost(String url, String bodyJson, String module, String token) throws Exception {
        String result = http.post()
                .uri(url)
                .header("Content-Type", "text/plain")
                .header("Module", module)
                .header("Source", "WEB")
                .header("AuthToken", token != null ? token : "DEFAULT")
                .body(bodyJson)
                .retrieve()
                .body(String.class);
        if (result != null && result.charAt(0) == '\uFEFF') {
            result = result.substring(1);
        }
        return result;
    }

    private String resolveUid(String accessToken) {
        if (accessToken == null) return null;
        if (accessToken.contains(":")) return accessToken.split(":")[0];
        for (CachedSession session : sessionCache.values()) {
            if (accessToken.equals(session.token)) return session.uid;
        }
        return accessToken;
    }

    private String extractToken(String accessToken) {
        if (accessToken == null) return null;
        if (accessToken.contains(":")) return accessToken.substring(accessToken.indexOf(':') + 1);
        return accessToken;
    }

    private String extractUid(String token) {
        if (token == null) return "";
        return resolveUid(token);
    }

    public static String generateTotp(String secret) {
        try {
            byte[] key = Base32Decode(secret.toUpperCase().replace(" ", ""));
            long counter = System.currentTimeMillis() / 30000L;
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (counter & 0xFF);
                counter >>= 8;
            }
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0F;
            int code = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", code % 1000000);
        } catch (Exception e) {
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    private static byte[] Base32Decode(String input) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        input = input.replaceAll("=", "").toUpperCase();
        int numBytes = input.length() * 5 / 8;
        byte[] result = new byte[numBytes];
        int bits = 0, value = 0, index = 0;
        for (char c : input.toCharArray()) {
            int idx = alphabet.indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("Invalid base32 char: " + c);
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                result[index++] = (byte) (value >> bits);
            }
        }
        return result;
    }
}
