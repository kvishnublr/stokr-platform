package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MotilalOswalAdapter implements BrokerAdapter {

    private static final String MOFSL_BASE = "https://openapi.motilaloswal.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;
    private final BrokerAccountRepository repository;

    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    // Scripmaster: maps "NSEFO|scripname" → scripcode (refreshed daily)
    private volatile Map<String, Integer> scripMaster = Collections.emptyMap();
    private volatile long scripMasterLoadedAt = 0;
    private static final long SCRIP_MASTER_TTL = 6 * 60 * 60 * 1000L; // 6 hours

    public MotilalOswalAdapter(RestClient.Builder restClientBuilder, BrokerAccountRepository repository) {
        this.http = restClientBuilder.build();
        this.repository = repository;
    }

    private record CachedSession(String token, String clientCode, String apiKey, String apiSecret, long expiresAt) {
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

    // ---- Scripmaster: resolve text trading symbol → numeric scrip code ----

    private void ensureScripMaster(String exchange) {
        if (!scripMaster.isEmpty() && (System.currentTimeMillis() - scripMasterLoadedAt) < SCRIP_MASTER_TTL) {
            return;
        }
        try {
            loadScripMasterCsv(exchange);
        } catch (Exception e) {
            log.warn("MOFSL: scripmaster CSV load failed for {}: {}", exchange, e.getMessage());
        }
    }

    private synchronized void loadScripMasterCsv(String exchange) {
        if (!scripMaster.isEmpty() && (System.currentTimeMillis() - scripMasterLoadedAt) < SCRIP_MASTER_TTL) {
            return; // another thread loaded it
        }
        String url = MOFSL_BASE + "/getscripmastercsv?name=" + exchange;
        log.info("MOFSL: downloading scripmaster CSV from {}", url);
        try {
            String csv = http.get().uri(url)
                    .header("Accept", "text/csv")
                    .retrieve().body(String.class);
            if (csv == null || csv.isBlank()) {
                log.warn("MOFSL: scripmaster CSV empty for {}", exchange);
                return;
            }
            Map<String, Integer> newMap = new ConcurrentHashMap<>();
            try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
                String headerLine = reader.readLine();
                if (headerLine == null) return;
                // CSV columns: exchange,exchangename,scripcode,scripname,marketlot,scripshortname,...
                String[] headers = headerLine.split(",", -1);
                int scripCodeIdx = -1, scripNameIdx = -1, exchangeIdx = -1;
                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i].trim().toLowerCase();
                    if ("scripcode".equals(h)) scripCodeIdx = i;
                    else if ("scripname".equals(h)) scripNameIdx = i;
                    else if ("exchange".equals(h) || "exchangename".equals(h)) exchangeIdx = i;
                }
                if (scripCodeIdx < 0 || scripNameIdx < 0) {
                    log.warn("MOFSL: scripmaster CSV missing scripcode/scripname columns. Headers: {}", headerLine);
                    return;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] cols = line.split(",", -1);
                    if (cols.length <= Math.max(scripCodeIdx, scripNameIdx)) continue;
                    try {
                        int code = Integer.parseInt(cols[scripCodeIdx].trim());
                        String name = cols[scripNameIdx].trim().toUpperCase();
                        String exch = exchangeIdx >= 0 && cols.length > exchangeIdx
                                ? cols[exchangeIdx].trim().toUpperCase() : exchange;
                        
                        newMap.put(exch + "|" + name, code);
                        
                        try {
                            String[] parts = name.split(" ");
                            if (parts.length >= 4) {
                                String underlying = parts[0];
                                String dateStr = parts[1]; // 29-Sep-2026
                                String type = parts[2]; // CE
                                String strikeStr = parts[3]; // 24200
                                String[] dateParts = dateStr.split("-");
                                if (dateParts.length == 3) {
                                    int day = Integer.parseInt(dateParts[0]);
                                    String monStr = dateParts[1].toUpperCase();
                                    int year = Integer.parseInt(dateParts[2]);
                                    int yy = year % 100;
                                    int strike = (int) Double.parseDouble(strikeStr);
                                    
                                    int month = 0;
                                    switch (monStr) {
                                        case "JAN": month = 1; break;
                                        case "FEB": month = 2; break;
                                        case "MAR": month = 3; break;
                                        case "APR": month = 4; break;
                                        case "MAY": month = 5; break;
                                        case "JUN": month = 6; break;
                                        case "JUL": month = 7; break;
                                        case "AUG": month = 8; break;
                                        case "SEP": month = 9; break;
                                        case "OCT": month = 10; break;
                                        case "NOV": month = 11; break;
                                        case "DEC": month = 12; break;
                                    }
                                    
                                    String mCode = (month == 10) ? "O" : (month == 11) ? "N" : (month == 12) ? "D" : String.valueOf(month);
                                    
                                    String cand1 = String.format("%s%02d%s%d%s", underlying, yy, monStr, strike, type);
                                    String cand2 = String.format("%s%02d%s%02d%d%s", underlying, yy, mCode, day, strike, type);
                                    String cand3 = String.format("%s%02d%d%02d%d%s", underlying, yy, month, day, strike, type);
                                    
                                    newMap.put(exch + "|" + cand1, code);
                                    newMap.put(exch + "|" + cand2, code);
                                    newMap.put(exch + "|" + cand3, code);
                                }
                            }
                        } catch (Exception ignored) {}

                    } catch (NumberFormatException ignored) {}
                }
            }
            log.info("MOFSL: scripmaster loaded {} entries for {}", newMap.size(), exchange);
            scripMaster = newMap;
            scripMasterLoadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.error("MOFSL: scripmaster download failed: {}", e.getMessage());
        }
    }

    private Integer resolveScripCode(String exchange, String tradingSymbol) {
        ensureScripMaster(exchange);
        String key = exchange.toUpperCase() + "|" + tradingSymbol.toUpperCase();
        Integer code = scripMaster.get(key);
        if (code != null) return code;
        // Try without exchange prefix (some entries use different exchange naming)
        for (var entry : scripMaster.entrySet()) {
            if (entry.getKey().endsWith("|" + tradingSymbol.toUpperCase())) {
                return entry.getValue();
            }
        }
        log.warn("MOFSL: no scripcode found for {} on {}", tradingSymbol, exchange);
        return null;
    }

    // ---- Auth ----

    public BrokerAccount connectWithTotp(Long userId, String clientCode, String password,
                                          String totpSecret, String apiKey, String apiSecret,
                                          String dob) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MOFSL API key is required. Get it from the Motilal Oswal developer portal.");
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
        account.setMofslApiKey(apiKey);
        account.setMofslApiSecret(apiSecret);
        if (dob != null && !dob.isBlank()) account.setMofslDob(dob.trim());
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
        String apiKey = account.getMofslApiKey();

        if (clientCode == null || clientCode.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("MOFSL client code and password are required.");
        }
        if (totpSecret == null || totpSecret.isBlank()) {
            throw new IllegalStateException("MOFSL TOTP secret is required.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MOFSL API key is required.");
        }

        CachedSession cached = sessionCache.get(account.getId());
        if (cached != null && !cached.isExpired()) {
            log.debug("MOFSL: reusing cached session for account {}", account.getId());
            return cached.token;
        }

        String otp = TotpUtils.generate(totpSecret);
        String hashedPassword = sha256(password + apiKey);
        String dob = account.getMofslDob();
        log.info("MOFSL: logging in with TOTP for clientCode={}, hasDob={}", clientCode, dob != null);

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("userid", clientCode);
        loginBody.put("password", hashedPassword);
        loginBody.put("2FA", dob != null && !dob.isBlank() ? dob : otp);
        loginBody.put("totp", otp);

        try {
            String respJson = mofslPost("/rest/login/v7/authdirectapi", loginBody, null, apiKey, account.getMofslApiSecret());
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("status").asText("");
            if (!"SUCCESS".equalsIgnoreCase(status)) {
                throw new RuntimeException("MOFSL login failed: " + root.path("message").asText(respJson));
            }
            String token = root.path("AuthToken").asText(null);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("MOFSL login returned no AuthToken");
            }
            sessionCache.put(account.getId(), new CachedSession(token, clientCode, apiKey, account.getMofslApiSecret(),
                    System.currentTimeMillis() + 8 * 60 * 60 * 1000));
            log.info("MOFSL: login successful for clientCode={}", clientCode);
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MOFSL login error: " + e.getMessage(), e);
        }
    }

    private record ResolvedAccount(String token, String clientCode, String apiKey, String apiSecret) {}

    private ResolvedAccount ensureToken(String accessToken) {
        for (var entry : sessionCache.entrySet()) {
            CachedSession c = entry.getValue();
            if (c.token.equals(accessToken) && !c.isExpired()) {
                return new ResolvedAccount(accessToken, c.clientCode, c.apiKey, c.apiSecret);
            }
        }
        List<BrokerAccount> accounts = repository.findByBrokerNameAndStatus("MOTILALOSWAL", "ACTIVE");
        if (!accounts.isEmpty()) {
            BrokerAccount acct = accounts.get(0);
            try {
                String token = login(acct);
                return new ResolvedAccount(token, acct.getClientId(), acct.getMofslApiKey(), acct.getMofslApiSecret());
            } catch (Exception e) {
                log.warn("MOFSL re-login failed: {}", e.getMessage());
                return new ResolvedAccount(accessToken, acct.getClientId(), acct.getMofslApiKey(), acct.getMofslApiSecret());
            }
        }
        return new ResolvedAccount(accessToken, "", "", "");
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

    // ---- Order placement ----

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("MOFSL: placing order {} {} {} qty={}", request.side(), request.symbol(), request.orderType(), request.quantity());
        ResolvedAccount resolved = ensureToken(accessToken);

        String mofslExchange = mapExchange(request.exchange());

        // Resolve text symbol → numeric scrip code
        Integer scripCode = resolveScripCode(mofslExchange, request.symbol());
        if (scripCode == null) {
            log.error("MOFSL: cannot resolve scripcode for symbol={} exchange={}", request.symbol(), mofslExchange);
            return new BrokerOrderResponse(null, "REJECTED",
                    "Symbol not found in MOFSL scripmaster: " + request.symbol() + " on " + mofslExchange);
        }
        log.info("MOFSL: resolved {} → scripcode {}", request.symbol(), scripCode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientcode", resolved.clientCode);
        body.put("exchange", mofslExchange);
        body.put("symboltoken", scripCode);
        body.put("buyorsell", request.side().name());
        body.put("ordertype", request.price() != null && request.price() > 0 ? "LIMIT" : "MARKET");
        String prod = request.productType() != null ? request.productType() : "NORMAL";
        if (prod.equalsIgnoreCase("NORMAL") || prod.equalsIgnoreCase("NRML") || prod.equalsIgnoreCase("MIS")) prod = "Normal";
        body.put("producttype", prod);
        body.put("orderduration", "DAY");
        body.put("price", request.price() != null ? request.price() : 0.0);
        body.put("triggerprice", 0.0);
        int lots = 1;
        body.put("quantityinlot", lots);
        body.put("disclosedquantity", 0);
        body.put("amoorder", "N");
        body.put("algoid", "");
        body.put("goodtilldate", "");
        body.put("tag", "STOKR");

        try {
            String respJson = mofslPost("/rest/trans/v2/placeorder", body, resolved.token, resolved.apiKey, resolved.apiSecret);
            JsonNode root = MAPPER.readTree(respJson);
            String status = root.path("status").asText("");
            String message = root.path("message").asText("");
            if ("SUCCESS".equalsIgnoreCase(status)) {
                String orderId = root.path("uniqueorderid").asText(root.path("orderid").asText(null));
                log.info("MOFSL order placed: {} (scrip={}) -> {} (message={})", request.symbol(), scripCode, orderId, message);
                return new BrokerOrderResponse(orderId, "OPEN", message);
            }
            log.warn("MOFSL order REJECTED: symbol={} scrip={} side={} qty={} status={} message={}",
                    request.symbol(), scripCode, request.side(), request.quantity(), status, message);
            return new BrokerOrderResponse(null, "REJECTED", message);
        } catch (Exception e) {
            log.error("MOFSL placeOrder failed for {} (scrip={}): {}", request.symbol(), scripCode, e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("MOFSL: cancelling order {}", orderId);
        ResolvedAccount resolved = ensureToken(accessToken);
        Map<String, Object> body = Map.of("uniqueorderid", orderId);
        try {
            mofslPost("/rest/trans/v1/cancelorder", body, resolved.token, resolved.apiKey, resolved.apiSecret);
        } catch (Exception e) {
            log.warn("MOFSL cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("MOFSL: fetching positions");
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            String respJson = mofslPost("/rest/book/v4/getposition", Map.of(), resolved.token, resolved.apiKey, resolved.apiSecret);
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
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            String respJson = mofslPost("/rest/report/v3/getreportmarginsummary", Map.of(), resolved.token, resolved.apiKey, resolved.apiSecret);
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
        ResolvedAccount resolved = ensureToken(accessToken);
        try {
            String respJson = mofslPost("/rest/book/v5/getorderbook", Map.of(), resolved.token, resolved.apiKey, resolved.apiSecret);
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

    // ---- HTTP ----

    private String mofslPost(String path, Map<String, Object> body, String token,
                              String apiKey, String apiSecret) throws Exception {
        String bodyJson = MAPPER.writeValueAsString(body);
        String serverIp = System.getProperty("server.public-ip", "173.249.55.84");
        var spec = http.post()
                .uri(MOFSL_BASE + path)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Content-Type", "application/json")
                .header("ApiKey", apiKey != null ? apiKey : "")
                .header("SourceId", "WEB")
                .header("vendorinfo", "STOKR")
                .header("ClientLocalIp", serverIp)
                .header("ClientPublicIp", serverIp)
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
