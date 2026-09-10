package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MotilalOswalAdapter implements BrokerAdapter {

    private static final String XTS_BASE = "https://moxtsapi.motilaloswal.com:3000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrokerAccountRepository repository;

    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    // exchangeInstrumentID cache: "NSEFO|NIFTY2691523500CE" → instrumentID
    private volatile Map<String, Long> instrumentCache = new ConcurrentHashMap<>();

    public MotilalOswalAdapter(BrokerAccountRepository repository) {
        this.repository = repository;
    }

    private record CachedSession(String token, String userId, String clientCode,
                                  String apiKey, String apiSecret, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    @PostConstruct
    public void autoLoginOnStartup() {
        try {
            List<BrokerAccount> accounts = repository.findByBrokerNameAndStatus("MOTILALOSWAL", "ACTIVE");
            for (BrokerAccount acct : accounts) {
                if (acct.getMofslApiKey() != null && acct.getMofslApiSecret() != null) {
                    log.info("MOFSL-XTS: auto-login on startup for clientCode={}", acct.getClientId());
                    try {
                        String token = login(acct);
                        acct.setAccessToken(token);
                        repository.save(acct);
                        log.info("MOFSL-XTS: startup login successful for account {}", acct.getId());
                    } catch (Exception e) {
                        log.warn("MOFSL-XTS: startup login failed for {}: {}", acct.getClientId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MOFSL-XTS: startup auto-login error: {}", e.getMessage());
        }
    }

    @Override
    public String getBrokerName() { return "MOTILALOSWAL"; }

    @Override
    public String getAuthUrl() {
        throw new UnsupportedOperationException("Motilal Oswal XTS uses API key/secret auth, not OAuth.");
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        throw new UnsupportedOperationException("Motilal Oswal XTS uses API key/secret auth, not OAuth.");
    }

    // ---- Auth ----

    public BrokerAccount connectWithTotp(Long userId, String clientCode, String password,
                                          String totpSecret, String apiKey, String apiSecret,
                                          String dob) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MOFSL XTS Interactive API key is required.");
        }
        if (apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("MOFSL XTS Interactive API secret is required.");
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
        account.setMofslApiKey(apiKey);       // XTS Interactive API Key
        account.setMofslApiSecret(apiSecret); // XTS Interactive API Secret
        if (dob != null && !dob.isBlank()) account.setMofslDob(dob.trim());
        account.setTokenExpiry(java.time.Instant.now().plusSeconds(365L * 24 * 3600));
        BrokerAccount saved = repository.save(account);
        try {
            String token = login(saved);
            saved.setAccessToken(token);
            repository.save(saved);
            log.info("MOFSL-XTS connected for user {}, clientCode={}", userId, clientCode);
        } catch (Exception e) {
            log.warn("MOFSL-XTS initial login failed (credentials saved anyway): {}", e.getMessage());
        }
        return saved;
    }

    private String login(BrokerAccount account) {
        String apiKey = account.getMofslApiKey();
        String apiSecret = account.getMofslApiSecret();

        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("MOFSL XTS API key and secret are required.");
        }

        CachedSession cached = sessionCache.get(account.getId());
        if (cached != null && !cached.isExpired()) {
            log.debug("MOFSL-XTS: reusing cached session for account {}", account.getId());
            return cached.token;
        }

        log.info("MOFSL-XTS: logging in for clientCode={}", account.getClientId());

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("secretKey", apiSecret);
        loginBody.put("appKey", apiKey);
        loginBody.put("source", "WEBAPI");

        try {
            String respJson = xtsPost("/interactive/user/session", loginBody, null);
            JsonNode root = MAPPER.readTree(respJson);
            String type = root.path("type").asText("");
            if (!"success".equalsIgnoreCase(type)) {
                throw new RuntimeException("MOFSL-XTS login failed: " + root.path("description").asText(respJson));
            }
            JsonNode result = root.path("result");
            String token = result.path("token").asText(null);
            String userId = result.path("userID").asText("");
            if (token == null || token.isBlank()) {
                throw new RuntimeException("MOFSL-XTS login returned no token");
            }
            sessionCache.put(account.getId(), new CachedSession(token, userId,
                    account.getClientId(), apiKey, apiSecret,
                    System.currentTimeMillis() + 23 * 60 * 60 * 1000)); // 23h (tokens expire at 6AM next day)
            log.info("MOFSL-XTS: login successful, userID={}", userId);
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MOFSL-XTS login error: " + e.getMessage(), e);
        }
    }

    private record ResolvedAccount(String token, String userId, String clientCode, String apiKey, String apiSecret) {}

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
                return new ResolvedAccount(token, acct.getClientId(), acct.getClientId(),
                        acct.getMofslApiKey(), acct.getMofslApiSecret());
            } catch (Exception e) {
                log.warn("MOFSL-XTS re-login failed: {}", e.getMessage());
                return new ResolvedAccount(accessToken, acct.getClientId(), acct.getClientId(),
                        acct.getMofslApiKey(), acct.getMofslApiSecret());
            }
        }
        return new ResolvedAccount(accessToken, "", "", "", "");
    }

    private static String mapExchangeSegment(String exchange) {
        if (exchange == null) return "NSEFO";
        return switch (exchange.toUpperCase()) {
            case "NFO", "NSEFO" -> "NSEFO";
            case "NSE", "NSECM" -> "NSECM";
            case "BSE", "BSECM" -> "BSECM";
            case "MCX", "MCXFO" -> "MCXFO";
            case "NSECD", "CDS" -> "NSECD";
            case "BSEFO", "BFO" -> "BSEFO";
            default -> exchange;
        };
    }

    private static String mapProductType(String productType) {
        if (productType == null) return "NRML";
        return switch (productType.toUpperCase()) {
            case "MIS", "INTRADAY" -> "MIS";
            case "NRML", "NORMAL", "CARRYFORWARD" -> "NRML";
            case "CNC", "DELIVERY" -> "CNC";
            case "CO", "COVER" -> "CO";
            case "BO", "BRACKET" -> "BO";
            default -> "NRML";
        };
    }

    private static String mapOrderType(BrokerOrderRequest request) {
        if (request.price() != null && request.price() > 0) {
            return "LIMIT";
        }
        return "MARKET";
    }

    // ---- Order placement ----

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("MOFSL-XTS: placing order {} {} {} qty={}", request.side(), request.symbol(), request.orderType(), request.quantity());
        ResolvedAccount resolved = ensureToken(accessToken);

        String exchangeSegment = mapExchangeSegment(request.exchange());

        // XTS uses exchangeInstrumentID (numeric) — we need to resolve from trading symbol
        Long instrumentId = resolveInstrumentId(exchangeSegment, request.symbol(), resolved.token);
        if (instrumentId == null) {
            log.error("MOFSL-XTS: cannot resolve instrumentID for symbol={} exchange={}", request.symbol(), exchangeSegment);
            return new BrokerOrderResponse(null, "REJECTED",
                    "Symbol not found in XTS: " + request.symbol() + " on " + exchangeSegment);
        }
        log.info("MOFSL-XTS: resolved {} → instrumentID {}", request.symbol(), instrumentId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exchangeSegment", exchangeSegment);
        body.put("exchangeInstrumentID", instrumentId);
        body.put("productType", mapProductType(request.productType()));
        body.put("orderType", mapOrderType(request));
        body.put("orderSide", request.side().name());
        body.put("timeInForce", "DAY");
        body.put("disclosedQuantity", 0);
        body.put("orderQuantity", request.quantity());
        body.put("limitPrice", request.price() != null ? request.price() : 0.0);
        body.put("stopPrice", 0.0);
        body.put("orderUniqueIdentifier", "STOKR_" + System.currentTimeMillis());
        body.put("clientID", resolved.userId);

        try {
            String respJson = xtsPost("/interactive/orders", body, resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            String type = root.path("type").asText("");
            String description = root.path("description").asText("");
            if ("success".equalsIgnoreCase(type)) {
                JsonNode result = root.path("result");
                String orderId = result.path("AppOrderID").asText(null);
                if (orderId == null) orderId = result.asText(null);
                log.info("MOFSL-XTS order placed: {} (instrID={}) -> AppOrderID={}", request.symbol(), instrumentId, orderId);
                return new BrokerOrderResponse(orderId, "OPEN", description);
            }
            log.warn("MOFSL-XTS order REJECTED: symbol={} instrID={} side={} qty={} desc={}",
                    request.symbol(), instrumentId, request.side(), request.quantity(), description);
            return new BrokerOrderResponse(null, "REJECTED", description);
        } catch (Exception e) {
            log.error("MOFSL-XTS placeOrder failed for {} (instrID={}): {}", request.symbol(), instrumentId, e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("MOFSL-XTS: cancelling order {}", orderId);
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            xtsDelete("/interactive/orders?appOrderID=" + orderId, resolved.token);
        } catch (Exception e) {
            log.warn("MOFSL-XTS cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("MOFSL-XTS: fetching positions");
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            String respJson = xtsGet("/interactive/portfolio/positions?dayOrNet=DayWise&clientID=" + resolved.userId, resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            String type = root.path("type").asText("");
            if (!"success".equalsIgnoreCase(type)) {
                log.warn("MOFSL-XTS getPositions failed: {}", root.path("description").asText());
                return Collections.emptyList();
            }
            JsonNode positionList = root.path("result").path("positionList");
            List<BrokerPosition> result = new ArrayList<>();
            if (positionList.isArray()) {
                for (JsonNode p : positionList) {
                    int buyQty = p.path("Quantity").path("BuyQuantity").asInt(
                            p.path("BuyQty").asInt(p.path("buyQty").asInt(0)));
                    int sellQty = p.path("Quantity").path("SellQuantity").asInt(
                            p.path("SellQty").asInt(p.path("sellQty").asInt(0)));
                    int qty = buyQty - sellQty;
                    if (qty == 0) continue;

                    double buyAvg = p.path("BuyAveragePrice").asDouble(p.path("buyAvgPrice").asDouble(0));
                    double sellAvg = p.path("SellAveragePrice").asDouble(p.path("sellAvgPrice").asDouble(0));
                    BigDecimal avgPrice = qty > 0
                            ? BigDecimal.valueOf(buyAvg)
                            : BigDecimal.valueOf(sellAvg);
                    BigDecimal ltp = BigDecimal.valueOf(p.path("LastTradedPrice").asDouble(
                            p.path("ltp").asDouble(0)));
                    BigDecimal mtm = BigDecimal.valueOf(p.path("RealizedMTM").asDouble(
                            p.path("realizedMTM").asDouble(0)));
                    BigDecimal unrealized = BigDecimal.valueOf(p.path("UnrealizedMTM").asDouble(
                            p.path("unrealizedMTM").asDouble(0)));

                    result.add(new BrokerPosition(
                            p.path("TradingSymbol").asText(p.path("tradingSymbol").asText("")),
                            p.path("ExchangeSegment").asText(p.path("exchangeSegment").asText("NSEFO")),
                            qty,
                            avgPrice,
                            ltp,
                            unrealized,
                            mtm,
                            p.path("ProductType").asText(p.path("productType").asText("NRML"))
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("MOFSL-XTS getPositions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("MOFSL-XTS: fetching available margin");
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            String respJson = xtsGet("/interactive/user/balance?clientID=" + resolved.userId, resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            String type = root.path("type").asText("");
            if ("success".equalsIgnoreCase(type)) {
                JsonNode result = root.path("result");
                JsonNode balList = result.path("BalanceList");
                if (balList.isArray()) {
                    for (JsonNode bal : balList) {
                        String limitHeader = bal.path("limitHeader").asText("");
                        if ("Net margin available".equalsIgnoreCase(limitHeader)
                                || "Cash Available".equalsIgnoreCase(limitHeader)
                                || limitHeader.toLowerCase().contains("net margin")) {
                            double marginValue = bal.path("marginAvailable").asDouble(
                                    bal.path("limitBranch").asDouble(0));
                            if (marginValue > 0) {
                                log.info("MOFSL-XTS: available margin={} ({})", marginValue, limitHeader);
                                return BigDecimal.valueOf(marginValue);
                            }
                        }
                    }
                    // fallback: sum all marginAvailable
                    double total = 0;
                    for (JsonNode bal : balList) {
                        total += bal.path("marginAvailable").asDouble(0);
                    }
                    if (total > 0) {
                        log.info("MOFSL-XTS: available margin (sum)={}", total);
                        return BigDecimal.valueOf(total);
                    }
                }
                // Try flat result fields
                double netMargin = result.path("netMarginAvailable").asDouble(
                        result.path("cashAvailable").asDouble(0));
                if (netMargin > 0) {
                    log.info("MOFSL-XTS: available margin={}", netMargin);
                    return BigDecimal.valueOf(netMargin);
                }
            }
            log.warn("MOFSL-XTS margin fetch failed: {}", root.path("description").asText());
        } catch (Exception e) {
            log.warn("MOFSL-XTS getAvailableMargin failed: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            String respJson = xtsGet("/interactive/orders?clientID=" + resolved.userId, resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            String type = root.path("type").asText("");
            if ("success".equalsIgnoreCase(type)) {
                JsonNode orders = root.path("result");
                if (orders.isArray()) {
                    for (JsonNode o : orders) {
                        String appOrderId = String.valueOf(o.path("AppOrderID").asInt(0));
                        if (orderId.equals(appOrderId)) {
                            String status = o.path("OrderStatus").asText(
                                    o.path("orderStatus").asText("UNKNOWN"));
                            return mapXtsOrderStatus(status);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MOFSL-XTS getOrderStatus {} failed: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }

    private String mapXtsOrderStatus(String xtsStatus) {
        if (xtsStatus == null) return "UNKNOWN";
        return switch (xtsStatus.toLowerCase()) {
            case "new", "open", "pendnew", "pendingnew" -> "OPEN";
            case "filled", "completely filled" -> "COMPLETE";
            case "partially filled", "partfilled" -> "PARTIAL";
            case "cancelled", "canceled" -> "CANCELLED";
            case "rejected" -> "REJECTED";
            default -> xtsStatus.toUpperCase();
        };
    }

    // ---- Instrument ID resolution ----
    // XTS uses numeric exchangeInstrumentID. We search the master or use the symbol directly.

    private Long resolveInstrumentId(String exchangeSegment, String tradingSymbol, String token) {
        String key = exchangeSegment + "|" + tradingSymbol.toUpperCase();
        Long cached = instrumentCache.get(key);
        if (cached != null) return cached;

        // Try XTS search API to find the instrument
        try {
            Map<String, Object> searchBody = new LinkedHashMap<>();
            searchBody.put("searchString", tradingSymbol);
            searchBody.put("source", "WEBAPI");

            String respJson = xtsPost("/interactive/search/instrumentsbystring", searchBody, token);
            JsonNode root = MAPPER.readTree(respJson);
            if ("success".equalsIgnoreCase(root.path("type").asText(""))) {
                JsonNode results = root.path("result");
                if (results.isArray()) {
                    for (JsonNode instr : results) {
                        String segment = instr.path("ExchangeSegment").asText("");
                        String symbol = instr.path("DisplayName").asText(
                                instr.path("TradingSymbol").asText(""));
                        long instrId = instr.path("ExchangeInstrumentID").asLong(0);
                        if (instrId > 0 && segment.equalsIgnoreCase(exchangeSegment)
                                && symbol.toUpperCase().contains(tradingSymbol.toUpperCase())) {
                            instrumentCache.put(key, instrId);
                            return instrId;
                        }
                    }
                    // Fallback: take first match on the right exchange
                    for (JsonNode instr : results) {
                        String segment = instr.path("ExchangeSegment").asText("");
                        long instrId = instr.path("ExchangeInstrumentID").asLong(0);
                        if (instrId > 0 && segment.equalsIgnoreCase(exchangeSegment)) {
                            instrumentCache.put(key, instrId);
                            return instrId;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MOFSL-XTS: instrument search failed for {}: {}", tradingSymbol, e.getMessage());
        }

        // Try treating the symbol itself as a numeric ID (some callers may pass it directly)
        try {
            long id = Long.parseLong(tradingSymbol);
            instrumentCache.put(key, id);
            return id;
        } catch (NumberFormatException ignored) {}

        return null;
    }

    // ---- HTTP helpers ----

    private HttpClient buildClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private HttpRequest.Builder baseRequest(String path, String token) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(XTS_BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            builder = builder.header("Authorization", token);
        }
        return builder;
    }

    private String xtsPost(String path, Map<String, Object> body, String token) throws Exception {
        String bodyJson = MAPPER.writeValueAsString(body);
        HttpClient client = buildClient();
        HttpRequest request = baseRequest(path, token)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        log.info("MOFSL-XTS HTTP POST: {} hasToken={}", path, token != null && !token.isBlank());

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        log.info("MOFSL-XTS HTTP response: {} status={} body={}", path, response.statusCode(),
                responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
        return responseBody;
    }

    private String xtsGet(String path, String token) throws Exception {
        HttpClient client = buildClient();
        HttpRequest request = baseRequest(path, token)
                .GET()
                .build();

        log.info("MOFSL-XTS HTTP GET: {}", path);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        log.info("MOFSL-XTS HTTP response: {} status={} body={}", path, response.statusCode(),
                responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
        return responseBody;
    }

    private String xtsDelete(String path, String token) throws Exception {
        HttpClient client = buildClient();
        HttpRequest request = baseRequest(path, token)
                .DELETE()
                .build();

        log.info("MOFSL-XTS HTTP DELETE: {}", path);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        log.info("MOFSL-XTS HTTP response: {} status={} body={}", path, response.statusCode(),
                responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
        return responseBody;
    }
}
