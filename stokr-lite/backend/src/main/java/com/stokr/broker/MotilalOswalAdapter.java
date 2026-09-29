package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.marketdata.tick.KiteInstrumentTokenCache;
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
    private final KiteInstrumentTokenCache instrumentTokens;

    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    // exchangeInstrumentID cache: "NSEFO|NIFTY2691523500CE" → instrumentID
    private volatile Map<String, Long> instrumentCache = new ConcurrentHashMap<>();

    public MotilalOswalAdapter(BrokerAccountRepository repository, KiteInstrumentTokenCache instrumentTokens) {
        this.repository = repository;
        this.instrumentTokens = instrumentTokens;
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
                    sessionExpiry(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))));
            log.info("MOFSL-XTS: login successful, userID={}", userId);
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MOFSL-XTS login error: " + e.getMessage(), e);
        }
    }

    /**
     * When a cached XTS session must be discarded: XTS resets sessions overnight, so a login from
     * yesterday afternoon is dead by the next morning even though it is under 23h old. Expire at the
     * next 08:30 IST (before the open), and never later than 23h after login.
     */
    static long sessionExpiry(java.time.ZonedDateTime loginAt) {
        java.time.ZonedDateTime ist = loginAt.withZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.ZonedDateTime cutoff = ist.toLocalDate().atTime(8, 30).atZone(ist.getZone());
        if (!cutoff.isAfter(ist)) cutoff = cutoff.plusDays(1);
        long hardCap = ist.toInstant().toEpochMilli() + 23L * 60 * 60 * 1000;
        return Math.min(cutoff.toInstant().toEpochMilli(), hardCap);
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
            String respJson = xtsDelete("/interactive/orders?appOrderID=" + orderId
                    + (resolved.userId != null && !resolved.userId.isBlank() ? "&clientID=" + resolved.userId : ""),
                    resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            if (!"success".equalsIgnoreCase(root.path("type").asText(""))) {
                log.warn("MOFSL-XTS cancel order {} rejected: {}", orderId, root.path("description").asText(respJson));
            }
        } catch (Exception e) {
            log.warn("MOFSL-XTS cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("MOFSL-XTS: fetching positions");
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            // NetWise includes positions carried from earlier sessions; DayWise only shows today's trades.
            String respJson = xtsGet("/interactive/portfolio/positions?dayOrNet=NetWise&clientID=" + resolved.userId, resolved.token);
            JsonNode root = MAPPER.readTree(respJson);
            String type = root.path("type").asText("");
            if (!"success".equalsIgnoreCase(type)) {
                log.warn("MOFSL-XTS getPositions failed: {}", root.path("description").asText());
                return Collections.emptyList();
            }
            return parsePositions(root.path("result").path("positionList"));
        } catch (Exception e) {
            log.warn("MOFSL-XTS getPositions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * XTS positionList rows carry the net quantity as a (string) "Quantity" field, with
     * OpenBuyQuantity / OpenSellQuantity alongside; numeric fields often arrive as strings.
     * Older/alternate shapes (nested Quantity.BuyQuantity, BuyQty) are still accepted.
     */
    static List<BrokerPosition> parsePositions(JsonNode positionList) {
        List<BrokerPosition> result = new ArrayList<>();
        if (positionList == null || !positionList.isArray()) return result;
        for (JsonNode p : positionList) {
            int qty = netQuantity(p);
            if (qty == 0) continue;

            double buyAvg = num(p, "BuyAveragePrice", "buyAvgPrice");
            double sellAvg = num(p, "SellAveragePrice", "sellAvgPrice");
            result.add(new BrokerPosition(
                    p.path("TradingSymbol").asText(p.path("tradingSymbol").asText("")),
                    p.path("ExchangeSegment").asText(p.path("exchangeSegment").asText("NSEFO")),
                    qty,
                    BigDecimal.valueOf(qty > 0 ? buyAvg : sellAvg),
                    BigDecimal.valueOf(num(p, "LastTradedPrice", "ltp")),
                    BigDecimal.valueOf(num(p, "UnrealizedMTM", "unrealizedMTM")),
                    BigDecimal.valueOf(num(p, "RealizedMTM", "realizedMTM")),
                    p.path("ProductType").asText(p.path("productType").asText("NRML"))
            ));
        }
        return result;
    }

    private static int netQuantity(JsonNode p) {
        JsonNode q = p.path("Quantity");
        if (q.isValueNode() && !q.asText("").isBlank()) {
            return (int) Math.round(q.asDouble(0));                        // XTS: net qty (string)
        }
        if (q.isObject()) {                                                  // nested variant
            return q.path("BuyQuantity").asInt(0) - q.path("SellQuantity").asInt(0);
        }
        if (p.has("OpenBuyQuantity") || p.has("OpenSellQuantity")) {
            return (int) Math.round(num(p, "OpenBuyQuantity") - num(p, "OpenSellQuantity"));
        }
        return (int) Math.round(num(p, "BuyQty", "buyQty") - num(p, "SellQty", "sellQty"));
    }

    /** First present field as a number (XTS sends many numerics as strings). */
    private static double num(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull() && !v.asText("").isBlank()) return v.asDouble(0);
        }
        return 0;
    }

    /** Why the last margin fetch returned 0, or null when it succeeded. */
    private volatile String lastMarginError;

    @Override
    public String lastMarginError() { return lastMarginError; }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("MOFSL-XTS: fetching available margin");
        ResolvedAccount resolved = ensureToken(accessToken);
        String error = null;
        // Attempt 1: as logged in. Attempt 2: after a forced re-login when XTS rejects the session
        // (tokens are reset overnight but our cache would keep serving the stale one), or with the
        // client code when XTS rejects the user ID as clientID.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String respJson = xtsGet("/interactive/user/balance?clientID=" + resolved.userId, resolved.token);
                JsonNode root = MAPPER.readTree(respJson);
                if ("success".equalsIgnoreCase(root.path("type").asText(""))) {
                    Double margin = parseAvailableMargin(root.path("result"));
                    if (margin != null) {
                        log.info("MOFSL-XTS: available margin={}", margin);
                        lastMarginError = null;
                        return BigDecimal.valueOf(margin);
                    }
                    String body = respJson.length() > 300 ? respJson.substring(0, 300) + "…" : respJson;
                    error = "balance response had no recognisable margin field: " + body;
                    break;
                }
                String desc = root.path("description").asText(respJson);
                error = desc;
                if (attempt == 1 && isSessionError(root)) {
                    log.info("MOFSL-XTS: session rejected ({}), re-logging in", desc);
                    final String staleToken = resolved.token;
                    sessionCache.values().removeIf(c -> c.token.equals(staleToken));
                    resolved = ensureToken(null);
                    continue;
                }
                if (attempt == 1 && resolved.clientCode != null && !resolved.clientCode.isBlank()
                        && !resolved.clientCode.equals(resolved.userId)) {
                    resolved = new ResolvedAccount(resolved.token, resolved.clientCode, resolved.clientCode,
                            resolved.apiKey, resolved.apiSecret);
                    continue;
                }
                break;
            } catch (Exception e) {
                error = e.getMessage();
                break;
            }
        }
        log.warn("MOFSL-XTS margin fetch failed: {}", error);
        lastMarginError = error;
        return BigDecimal.ZERO;
    }

    /** XTS signals an expired/invalid session with an e-session-* code or a token message. */
    static boolean isSessionError(JsonNode root) {
        String code = root.path("code").asText("").toLowerCase();
        String desc = root.path("description").asText("").toLowerCase();
        return code.startsWith("e-session") || desc.contains("token") || desc.contains("session");
    }

    /**
     * XTS balance: result.BalanceList[].limitObject.RMSSubLimits.{netMarginAvailable, cashAvailable}
     * (values usually strings; limitHeader is e.g. "ALL|ALL|ALL"). Uses the ALL|ALL|ALL entry when
     * present (it already aggregates segments), otherwise the first entry with a value; null when no
     * known field is present. Legacy flat shapes are kept as fallbacks.
     */
    static Double parseAvailableMargin(JsonNode result) {
        Double first = null;
        JsonNode balList = result.path("BalanceList");
        if (balList.isArray()) {
            for (JsonNode bal : balList) {
                Double v = entryMargin(bal);
                if (v == null) continue;
                if (bal.path("limitHeader").asText("").toUpperCase().startsWith("ALL|ALL|ALL")) return v;
                if (first == null) first = v;
            }
        }
        if (first != null) return first;
        if (has(result, "netMarginAvailable")) return result.path("netMarginAvailable").asDouble(0);
        if (has(result, "cashAvailable")) return result.path("cashAvailable").asDouble(0);
        return null;
    }

    private static Double entryMargin(JsonNode bal) {
        JsonNode rms = bal.path("limitObject").path("RMSSubLimits");
        if (has(rms, "netMarginAvailable")) return rms.path("netMarginAvailable").asDouble(0);
        if (has(rms, "cashAvailable")) return rms.path("cashAvailable").asDouble(0);
        if (bal.path("marginAvailable").isValueNode() && has(bal, "marginAvailable")) {
            return bal.path("marginAvailable").asDouble(0);
        }
        return null;
    }

    private static boolean has(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return !v.isMissingNode() && !v.isNull() && !v.asText("").isBlank();
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
                        // AppOrderIDs can exceed int range — compare as text/long, never asInt()
                        String appOrderId = o.path("AppOrderID").isNumber()
                                ? String.valueOf(o.path("AppOrderID").asLong(0))
                                : o.path("AppOrderID").asText("");
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

    static String mapXtsOrderStatus(String xtsStatus) {
        if (xtsStatus == null) return "UNKNOWN";
        return switch (xtsStatus.toLowerCase()) {
            // XTS: New, PendingNew, Replaced, PendingReplace, PendingCancel are all still working orders
            case "new", "open", "pendnew", "pendingnew", "replaced", "pendingreplace", "pendingcancel" -> "OPEN";
            case "filled", "completely filled" -> "COMPLETE";
            case "partially filled", "partfilled", "partiallyfilled" -> "PARTIAL";
            case "cancelled", "canceled" -> "CANCELLED";
            case "rejected" -> "REJECTED";
            default -> xtsStatus.toUpperCase();
        };
    }

    // ---- Instrument ID resolution ----
    // XTS uses numeric exchangeInstrumentID, which for NSE F&O is NSE's own exchange token — the
    // same number Kite publishes as exchange_token. Resolve from that first; only accept an EXACT
    // symbol match from XTS search. Never guess: a wrong ID places the order on another contract.

    private Long resolveInstrumentId(String exchangeSegment, String tradingSymbol, String token) {
        String key = exchangeSegment + "|" + tradingSymbol.toUpperCase();
        Long cached = instrumentCache.get(key);
        if (cached != null) return cached;

        if ("NSEFO".equals(exchangeSegment) && instrumentTokens != null) {
            Long exchangeToken = instrumentTokens.getExchangeToken(tradingSymbol);
            if (exchangeToken != null && exchangeToken > 0) {
                instrumentCache.put(key, exchangeToken);
                return exchangeToken;
            }
        }

        // Fall back to XTS search, exact match only
        try {
            Map<String, Object> searchBody = new LinkedHashMap<>();
            searchBody.put("searchString", tradingSymbol);
            searchBody.put("source", "WEBAPI");

            String respJson = xtsPost("/interactive/search/instrumentsbystring", searchBody, token);
            JsonNode root = MAPPER.readTree(respJson);
            if ("success".equalsIgnoreCase(root.path("type").asText(""))) {
                JsonNode results = root.path("result");
                if (results.isArray()) {
                    Long exact = exactSearchMatch(results, tradingSymbol);
                    if (exact != null) {
                        instrumentCache.put(key, exact);
                        return exact;
                    }
                    // Deliberately no "first result" fallback: search hits for NIFTY... include
                    // every strike and expiry, and picking one would trade the wrong contract.
                    log.warn("MOFSL-XTS: no exact instrument match for {} among {} search results",
                            tradingSymbol, results.size());
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

    /** Instrument ID whose trading/display symbol equals {@code tradingSymbol} (ignoring case and spaces). */
    static Long exactSearchMatch(JsonNode results, String tradingSymbol) {
        String want = tradingSymbol.replace(" ", "").toUpperCase();
        for (JsonNode instr : results) {
            long instrId = instr.path("ExchangeInstrumentID").asLong(0);
            if (instrId <= 0) continue;
            for (String f : List.of("TradingSymbol", "Name", "DisplayName")) {
                String s = instr.path(f).asText("").replace(" ", "").toUpperCase();
                if (!s.isEmpty() && s.equals(want)) return instrId;
            }
        }
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
