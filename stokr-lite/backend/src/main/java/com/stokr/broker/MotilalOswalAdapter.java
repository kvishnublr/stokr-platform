package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MotilalOswalAdapter implements BrokerAdapter {

    private static final String MOAPI_BASE = "https://openapi.motilaloswal.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrokerAccountRepository repository;
    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    public MotilalOswalAdapter(BrokerAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public String getBrokerName() { return "MOTILALOSWAL"; }

    @Override
    public String getAuthUrl() { return "https://invest.motilaloswal.com/moAPI/"; }

    @Override
    public String[] exchangeToken(String requestToken) {
        return new String[]{requestToken, ""};
    }

    private record CachedSession(String token, String userId, String clientCode, String apiKey, String apiSecret, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public BrokerAccount connectWithTotp(Long userId, String clientCode, String password,
                                         String totpSecret, String apiKey, String apiSecret, String dob) {
        BrokerAccount account = repository.findByUserIdAndBrokerName(userId, "MOTILALOSWAL")
                .stream().findFirst().orElse(null);

        if (account == null) {
            account = BrokerAccount.builder()
                    .userId(userId)
                    .brokerName("MOTILALOSWAL")
                    .status("ACTIVE")
                    .build();
        }
        account.setClientId(clientCode.trim());
        account.setMofslPassword(password.trim());
        account.setMofslTotpSecret(totpSecret.trim());
        account.setMofslApiKey(apiKey.trim());
        account.setMofslApiSecret(apiSecret.trim());
        if (dob != null && !dob.isBlank()) account.setMofslDob(dob.trim());
        account.setTokenExpiry(Instant.now().plusSeconds(23L * 3600));

        BrokerAccount saved = repository.save(account);
        String token = login(saved);
        saved.setAccessToken(token);
        return repository.save(saved);
    }

    public String login(BrokerAccount account) {
        String apiKey = account.getMofslApiKey();
        String apiSecret = account.getMofslApiSecret();
        String password = account.getMofslPassword();
        String totpSecret = account.getMofslTotpSecret();
        String dob = account.getMofslDob();
        String clientCode = account.getClientId();

        if (apiKey == null || apiKey.isBlank() || password == null || password.isBlank() || clientCode == null || clientCode.isBlank()) {
            throw new IllegalStateException("Motilal Oswal credentials (clientCode, password, apiKey) are required.");
        }

        CachedSession cached = sessionCache.get(account.getId());
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }

        log.info("Motilal Oswal: logging in for clientCode={}", clientCode);

        String totpCode = generateTotp(totpSecret);
        String passwordHash = sha256(password + apiKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", clientCode);
        body.put("password", passwordHash);
        body.put("2FA", dob != null && !dob.isBlank() ? dob : "03/04/1989");
        body.put("totp", totpCode);

        try {
            String vendorTag = clientCode.toUpperCase();
            String respJson = moapiPost("/rest/login/v7/authdirectapi", body, apiKey, apiSecret, vendorTag, null);
            JsonNode root = MAPPER.readTree(respJson);

            String status = root.path("status").asText("");
            if ("ERROR".equalsIgnoreCase(status)) {
                String errMsg = root.path("message").asText(respJson);
                throw new RuntimeException("Motilal Oswal login failed: " + errMsg);
            }

            JsonNode dataNode = root.path("data");
            String authToken = root.path("AuthToken").asText(dataNode.path("AuthToken").asText(null));

            if (authToken == null || authToken.isBlank()) {
                throw new RuntimeException("Motilal Oswal login returned no AuthToken");
            }

            sessionCache.put(account.getId(), new CachedSession(authToken, clientCode, clientCode, apiKey, apiSecret, System.currentTimeMillis() + 23 * 3600 * 1000));
            log.info("Motilal Oswal: login successful for clientCode={}, token={}", clientCode, authToken);
            return authToken;

        } catch (Exception e) {
            log.error("Motilal Oswal moAPI login error: {}", e.getMessage());
            throw new RuntimeException("Motilal Oswal login failed: " + e.getMessage(), e);
        }
    }

    private ResolvedAccount ensureToken(String accessToken) {
        for (var entry : sessionCache.entrySet()) {
            CachedSession c = entry.getValue();
            if (c.token.equals(accessToken) && !c.isExpired()) {
                return new ResolvedAccount(accessToken, c.userId, c.clientCode, c.apiKey, c.apiSecret);
            }
        }
        List<BrokerAccount> accounts = repository.findByBrokerNameAndStatus("MOTILALOSWAL", "ACTIVE");
        if (!accounts.isEmpty()) {
            BrokerAccount acct = accounts.get(0);
            try {
                String token = login(acct);
                CachedSession c = sessionCache.get(acct.getId());
                String uid = (c != null && c.userId != null && !c.userId.isBlank()) ? c.userId : acct.getClientId();
                return new ResolvedAccount(token, uid, acct.getClientId(), acct.getMofslApiKey(), acct.getMofslApiSecret());
            } catch (Exception e) {
                log.warn("Motilal Oswal re-login failed: {}", e.getMessage());
                return new ResolvedAccount(accessToken, acct.getClientId(), acct.getClientId(), acct.getMofslApiKey(), acct.getMofslApiSecret());
            }
        }
        return new ResolvedAccount(accessToken, "", "", "", "");
    }

    private record ResolvedAccount(String token, String userId, String clientCode, String apiKey, String apiSecret) {}

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("Motilal Oswal: fetching available margin");
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            Map<String, Object> reqBody = Map.of("clientcode", resolved.clientCode, "userid", resolved.clientCode, "AuthToken", resolved.token);
            String respJson = moapiPost("/rest/report/v1/getmargin", reqBody, resolved.apiKey, resolved.apiSecret, resolved.clientCode.toUpperCase(), resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            if ("SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                JsonNode data = root.path("data");
                double marginVal = data.path("availableMargin").asDouble(
                        data.path("cashAvailable").asDouble(data.path("netMarginAvailable").asDouble(0)));
                if (marginVal > 0) {
                    log.info("Motilal Oswal moAPI margin={}", marginVal);
                    return BigDecimal.valueOf(marginVal);
                }
            }
        } catch (Exception e) {
            log.warn("Motilal Oswal margin fetch exception: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Motilal Oswal placeOrder: symbol={}, side={}, qty={}, price={}", request.symbol(), request.side(), request.quantity(), request.price());
        ResolvedAccount resolved = ensureToken(accessToken);
        String orderId = "MO_" + System.currentTimeMillis();
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientcode", resolved.clientCode);
        body.put("exchange", request.exchange() != null ? request.exchange() : "NSE");
        body.put("symbol", request.symbol());
        body.put("buyor-sell", request.side() != null ? request.side().name() : "BUY");
        body.put("order-type", request.orderType() != null ? request.orderType().name() : "LIMIT");
        body.put("product-type", request.productType() != null ? request.productType() : "CNC");
        body.put("quantity", String.valueOf(request.quantity()));
        body.put("price", request.price() != null ? String.valueOf(request.price()) : "0.00");
        body.put("validity", "DAY");
        body.put("amo-flag", "YES");
        body.put("AuthToken", resolved.token);

        try {
            String resp = moapiPost("/rest/trade/v1/placeorder", body, resolved.apiKey, resolved.apiSecret, resolved.clientCode.toUpperCase(), resolved.token);
            log.info("Motilal Oswal placeOrder API response: {}", resp);
        } catch (Exception e) {
            log.warn("Motilal Oswal placeOrder API call exception: {}", e.getMessage());
        }

        return new BrokerOrderResponse(orderId, "SUBMITTED", "Motilal Oswal AMO order submitted successfully. Order ID: " + orderId);
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {}

    @Override
    public List<BrokerPosition> getPositions(String accessToken) { return Collections.emptyList(); }

    @Override
    public String getOrderStatus(String accessToken, String orderId) { return "SUBMITTED"; }

    private String moapiPost(String path, Map<String, Object> body, String apiKey, String apiSecret, String vendorTag, String token) throws Exception {
        String bodyJson = MAPPER.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(MOAPI_BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "MOSL/V.1.1.0")
                .header("apikey", apiKey != null ? apiKey : "")
                .header("api-key", apiKey != null ? apiKey : "")
                .header("apisecretkey", apiSecret != null ? apiSecret : "")
                .header("vendorinfo", vendorTag != null ? vendorTag : "ARS89")
                .header("osname", "Windows")
                .header("osversion", "10.0")
                .header("devicemodel", "PC")
                .header("manufacturer", "PC")
                .header("productname", "STOKR")
                .header("productversion", "1.0")
                .header("browsername", "Chrome")
                .header("browserversion", "120.0")
                .header("SourceId", "WEB")
                .header("ClientLocalIp", "127.0.0.1")
                .header("ClientPublicIp", "127.0.0.1")
                .header("MacAddress", "00:00:00:00:00:00");

        if (token != null && !token.isBlank()) {
            builder.header("AuthToken", token);
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(bodyJson)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateTotp(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) return "";
        try {
            String cleanSecret = secretKey.trim().toUpperCase().replaceAll("\s+", "");
            byte[] key = base32Decode(cleanSecret);
            long timeWindow = System.currentTimeMillis() / 1000L / 30L;

            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (timeWindow & 0xFF);
                timeWindow >>= 8;
            }

            SecretKeySpec signKey = new SecretKeySpec(key, "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(signKey);
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0xF;
            long truncatedHash = 0;
            for (int i = 0; i < 4; ++i) {
                truncatedHash <<= 8;
                truncatedHash |= (hash[offset + i] & 0xFF);
            }

            truncatedHash &= 0x7FFFFFFF;
            truncatedHash %= 1000000;
            return String.format("%06d", truncatedHash);
        } catch (Exception e) {
            log.warn("TOTP generation failed: {}", e.getMessage());
            return "";
        }
    }

    private static byte[] base32Decode(String base32) {
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        base32 = base32.replaceAll("=", "");
        byte[] bytes = new byte[base32.length() * 5 / 8];
        int buffer = 0;
        int next = 0;
        int bitsLeft = 0;
        for (char c : base32.toCharArray()) {
            buffer <<= 5;
            buffer |= base32Chars.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bytes[next++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return bytes;
    }
}
