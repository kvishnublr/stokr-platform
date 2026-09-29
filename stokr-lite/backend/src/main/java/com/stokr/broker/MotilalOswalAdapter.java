package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motilal Oswal "MO API" (openapi.motilaloswal.com) — the API issued from the
 * invest.motilaloswal.com/moapi portal (API key + secret key + TOTP key, static IP).
 *
 * Login: client code + SHA-256(password + apiKey) + DOB (2FA) + TOTP. The connect form stores all of
 * these. (Between 10 Sep and this change the adapter spoke XTS instead, which does not accept MO API
 * keys — every call then failed with "Please Provide token to Authenticate".)
 */
@Slf4j
@Component
public class MotilalOswalAdapter implements BrokerAdapter {

    private static final String MOFSL_BASE = "https://openapi.motilaloswal.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BrokerAccountRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ConcurrentHashMap<Long, CachedSession> sessionCache = new ConcurrentHashMap<>();

    /** Scrip master entry: MO numeric scrip code and the contract's market lot. */
    record Scrip(int code, int lot) {}

    // "NSEFO|<symbol>" → scrip (MO scrip name plus Kite-style aliases), refreshed every 6 hours
    private volatile Map<String, Scrip> scripMaster = Collections.emptyMap();
    private volatile long scripMasterLoadedAt = 0;
    private static final long SCRIP_MASTER_TTL = 6 * 60 * 60 * 1000L;

    /** Why the last margin fetch returned 0, or null when it succeeded. */
    private volatile String lastMarginError;

    public MotilalOswalAdapter(BrokerAccountRepository repository) {
        this.repository = repository;
    }

    private record CachedSession(String token, String clientCode, String apiKey, String apiSecret, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    @PostConstruct
    public void autoLoginOnStartup() {
        try {
            for (BrokerAccount acct : repository.findByBrokerNameAndStatus("MOTILALOSWAL", "ACTIVE")) {
                if (acct.getMofslPassword() != null && acct.getMofslTotpSecret() != null && acct.getMofslApiKey() != null) {
                    log.info("MOFSL: auto-login on startup for clientCode={}", acct.getClientId());
                    try {
                        acct.setAccessToken(login(acct));
                        repository.save(acct);
                        log.info("MOFSL: startup login successful for account {}", acct.getId());
                    } catch (Exception e) {
                        log.warn("MOFSL: startup login failed for {}: {}", acct.getClientId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MOFSL: startup auto-login error: {}", e.getMessage());
        }
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

    // ---- Scrip master: trading symbol → scrip code + lot ----

    private void ensureScripMaster(String exchange) {
        if (!scripMaster.isEmpty() && (System.currentTimeMillis() - scripMasterLoadedAt) < SCRIP_MASTER_TTL) return;
        loadScripMasterCsv(exchange);
    }

    private synchronized void loadScripMasterCsv(String exchange) {
        if (!scripMaster.isEmpty() && (System.currentTimeMillis() - scripMasterLoadedAt) < SCRIP_MASTER_TTL) return;
        String url = MOFSL_BASE + "/getscripmastercsv?name=" + exchange;
        log.info("MOFSL: downloading scrip master from {}", url);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60)).header("Accept", "text/csv").GET().build();
            String csv = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
            Map<String, Scrip> parsed = parseScripMaster(csv, exchange);
            if (parsed.isEmpty()) {
                log.warn("MOFSL: scrip master empty/unparseable for {}", exchange);
                return;
            }
            scripMaster = parsed;
            scripMasterLoadedAt = System.currentTimeMillis();
            log.info("MOFSL: scrip master loaded {} entries for {}", parsed.size(), exchange);
        } catch (Exception e) {
            log.error("MOFSL: scrip master download failed: {}", e.getMessage());
        }
    }

    /**
     * Parses MO's scrip master CSV (exchange, exchangename, scripcode, scripname, marketlot, ...).
     * The file is downloaded per exchange, so every row is keyed under {@code exchange}.
     * Option rows ("NIFTY 29-Sep-2026 CE 24200") are also indexed under the Kite-style symbols the
     * rest of the platform uses: NIFTY26SEP24200CE (monthly) and NIFTY2692924200CE (weekly).
     */
    static Map<String, Scrip> parseScripMaster(String csv, String exchange) throws Exception {
        Map<String, Scrip> map = new HashMap<>();
        if (csv == null || csv.isBlank()) return map;
        String exch = exchange.toUpperCase();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return map;
            String[] headers = headerLine.split(",", -1);
            int codeIdx = -1, nameIdx = -1, lotIdx = -1;
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().toLowerCase();
                if ("scripcode".equals(h)) codeIdx = i;
                else if ("scripname".equals(h)) nameIdx = i;
                else if ("marketlot".equals(h)) lotIdx = i;
            }
            if (codeIdx < 0 || nameIdx < 0) return map;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length <= Math.max(codeIdx, nameIdx)) continue;
                int code;
                try { code = Integer.parseInt(cols[codeIdx].trim()); } catch (NumberFormatException e) { continue; }
                int lot = 0;
                if (lotIdx >= 0 && cols.length > lotIdx) {
                    try { lot = (int) Double.parseDouble(cols[lotIdx].trim()); } catch (NumberFormatException ignored) {}
                }
                String name = cols[nameIdx].trim().toUpperCase();
                Scrip scrip = new Scrip(code, lot);
                map.put(exch + "|" + name, scrip);
                for (String alias : kiteAliases(name)) map.putIfAbsent(exch + "|" + alias, scrip);
            }
        }
        return map;
    }

    /** Kite-style symbols for an MO option name like "NIFTY 29-SEP-2026 CE 24200". */
    static List<String> kiteAliases(String moName) {
        String[] parts = moName.split(" ");
        if (parts.length < 4) return List.of();
        String[] d = parts[1].split("-");
        if (d.length != 3) return List.of();
        try {
            int day = Integer.parseInt(d[0]);
            String mon = d[1].toUpperCase();
            int yy = Integer.parseInt(d[2]) % 100;
            int month = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
                    .indexOf(mon) + 1;
            if (month == 0) return List.of();
            String type = parts[2];
            String strike = new BigDecimal(parts[3]).stripTrailingZeros().toPlainString();
            String mCode = month == 10 ? "O" : month == 11 ? "N" : month == 12 ? "D" : String.valueOf(month);
            return List.of(
                    String.format("%s%02d%s%s%s", parts[0], yy, mon, strike, type),          // monthly
                    String.format("%s%02d%s%02d%s%s", parts[0], yy, mCode, day, strike, type)); // weekly
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Exact lookup only — never scans for a partial match, which could pick another contract. */
    private Scrip resolveScrip(String exchange, String tradingSymbol) {
        ensureScripMaster(exchange);
        Scrip s = scripMaster.get(exchange.toUpperCase() + "|" + tradingSymbol.toUpperCase());
        if (s == null) log.warn("MOFSL: no scrip found for {} on {}", tradingSymbol, exchange);
        return s;
    }

    // ---- Auth ----

    public BrokerAccount connectWithTotp(Long userId, String clientCode, String password,
                                          String totpSecret, String apiKey, String apiSecret,
                                          String dob) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MOFSL API key is required. Get it from the Motilal Oswal MO API portal.");
        }
        BrokerAccount account = repository.findByUserIdAndBrokerNameAndStatus(userId, "MOTILALOSWAL", "ACTIVE")
                .stream().findFirst().orElse(null);
        if (account == null) {
            account = repository.findByUserIdAndBrokerName(userId, "MOTILALOSWAL").stream().findFirst().orElse(null);
            if (account != null) account.setStatus("ACTIVE");
        }
        if (account == null) {
            account = BrokerAccount.builder().userId(userId).brokerName("MOTILALOSWAL").status("ACTIVE").build();
        }
        account.setClientId(clientCode);
        account.setMofslPassword(password);
        account.setMofslTotpSecret(totpSecret);
        account.setMofslApiKey(apiKey);
        account.setMofslApiSecret(apiSecret);
        if (dob != null && !dob.isBlank()) account.setMofslDob(dob.trim());
        account.setTokenExpiry(java.time.Instant.now().plusSeconds(365L * 24 * 3600));
        BrokerAccount saved = repository.save(account);
        sessionCache.remove(saved.getId()); // new credentials ⇒ new session
        try {
            saved.setAccessToken(login(saved));
            repository.save(saved);
            log.info("MOFSL connected for user {}, clientCode={}", userId, clientCode);
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
        if (totpSecret == null || totpSecret.isBlank()) throw new IllegalStateException("MOFSL TOTP secret is required.");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("MOFSL API key is required.");

        CachedSession cached = sessionCache.get(account.getId());
        if (cached != null && !cached.isExpired()) return cached.token;

        String otp = TotpUtils.generate(totpSecret);
        String dob = account.getMofslDob();
        log.info("MOFSL: logging in for clientCode={}, hasDob={}", clientCode, dob != null && !dob.isBlank());

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("userid", clientCode);
        loginBody.put("password", sha256(password + apiKey));
        loginBody.put("2FA", dob != null && !dob.isBlank() ? dob : otp);
        loginBody.put("totp", otp);

        try {
            String respJson = mofslPost("/rest/login/v7/authdirectapi", loginBody, null, apiKey,
                    account.getMofslApiSecret(), clientCode);
            JsonNode root = MAPPER.readTree(respJson);
            if (!"SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                throw new RuntimeException("MOFSL login failed: " + root.path("message").asText(respJson));
            }
            String token = root.path("AuthToken").asText(null);
            if (token == null || token.isBlank()) throw new RuntimeException("MOFSL login returned no AuthToken");
            sessionCache.put(account.getId(), new CachedSession(token, clientCode, apiKey,
                    account.getMofslApiSecret(), sessionExpiry(ZonedDateTime.now(IST))));
            log.info("MOFSL: login successful for clientCode={}", clientCode);
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MOFSL login error: " + e.getMessage(), e);
        }
    }

    /**
     * When a cached session must be discarded: the next 08:30 IST (so each trading day starts with a
     * fresh login), and never more than 8h after login.
     */
    static long sessionExpiry(ZonedDateTime loginAt) {
        ZonedDateTime ist = loginAt.withZoneSameInstant(IST);
        ZonedDateTime cutoff = ist.toLocalDate().atTime(8, 30).atZone(IST);
        if (!cutoff.isAfter(ist)) cutoff = cutoff.plusDays(1);
        long cap = ist.toInstant().toEpochMilli() + 8L * 60 * 60 * 1000;
        return Math.min(cutoff.toInstant().toEpochMilli(), cap);
    }

    private record ResolvedAccount(String token, String clientCode, String apiKey, String apiSecret) {}

    /** Session for this token, else a (re-)login of the active account. {@code login} may throw. */
    private ResolvedAccount ensureToken(String accessToken) {
        if (accessToken != null) {
            for (CachedSession c : sessionCache.values()) {
                if (c.token.equals(accessToken) && !c.isExpired()) {
                    return new ResolvedAccount(accessToken, c.clientCode, c.apiKey, c.apiSecret);
                }
            }
        }
        List<BrokerAccount> accounts = repository.findByBrokerNameAndStatus("MOTILALOSWAL", "ACTIVE");
        if (accounts.isEmpty()) throw new IllegalStateException("No active Motilal Oswal account");
        BrokerAccount acct = accounts.get(0);
        return new ResolvedAccount(login(acct), acct.getClientId(), acct.getMofslApiKey(), acct.getMofslApiSecret());
    }

    /** MO API rejects stale/missing sessions with an auth message instead of data. */
    static boolean isSessionError(JsonNode root) {
        String msg = (root.path("message").asText("") + " " + root.path("errorcode").asText("")).toLowerCase();
        return msg.contains("token") || msg.contains("session") || msg.contains("authori") || msg.contains("login");
    }

    /**
     * POST with one automatic re-login: when MO rejects the session, the cached token is dropped and
     * the call retried once with a fresh one.
     */
    private JsonNode call(String accessToken, String path, Map<String, Object> body) throws Exception {
        ResolvedAccount r = ensureToken(accessToken);
        JsonNode root = MAPPER.readTree(mofslPost(path, body, r.token, r.apiKey, r.apiSecret, r.clientCode));
        if (!"SUCCESS".equalsIgnoreCase(root.path("status").asText("")) && isSessionError(root)) {
            log.info("MOFSL: session rejected on {} ({}), re-logging in", path, root.path("message").asText());
            final String stale = r.token;
            sessionCache.values().removeIf(c -> c.token.equals(stale));
            r = ensureToken(null);
            root = MAPPER.readTree(mofslPost(path, body, r.token, r.apiKey, r.apiSecret, r.clientCode));
        }
        return root;
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

    /** MO takes F&O quantity in lots. Returns null when the quantity is not a whole number of lots. */
    static Integer lotsFor(int quantity, int lotSize) {
        if (lotSize <= 0) return null;
        if (quantity <= 0 || quantity % lotSize != 0) return null;
        return quantity / lotSize;
    }

    // ---- Orders ----

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("MOFSL: placing order {} {} {} qty={}", request.side(), request.symbol(), request.orderType(), request.quantity());
        String exchange = mapExchange(request.exchange());
        Scrip scrip = resolveScrip(exchange, request.symbol());
        if (scrip == null) {
            return new BrokerOrderResponse(null, "REJECTED",
                    "Symbol not found in MOFSL scrip master: " + request.symbol() + " on " + exchange);
        }
        int quantityInLot;
        if ("NSEFO".equals(exchange) || "BSEFO".equals(exchange) || "MCX".equals(exchange) || "NSECD".equals(exchange)) {
            Integer lots = lotsFor(request.quantity(), scrip.lot());
            if (lots == null) {
                return new BrokerOrderResponse(null, "REJECTED", "Quantity " + request.quantity()
                        + " is not a whole number of lots (lot size " + scrip.lot() + ") for " + request.symbol());
            }
            quantityInLot = lots;
        } else {
            quantityInLot = request.quantity(); // cash segment: shares
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exchange", exchange);
        body.put("symboltoken", scrip.code());
        body.put("buyorsell", request.side().name());
        body.put("ordertype", request.price() != null && request.price() > 0 ? "LIMIT" : "MARKET");
        // MO product types: NORMAL (carry-forward margin), DELIVERY, VALUEPLUS (intraday). NRML and MIS
        // both go as NORMAL so positions are never auto-squared-off by the broker.
        String prod = request.productType() != null ? request.productType().toUpperCase() : "NORMAL";
        if ("NRML".equals(prod) || "MIS".equals(prod)) prod = "NORMAL";
        body.put("producttype", prod);
        body.put("orderduration", "DAY");
        body.put("price", request.price() != null ? request.price() : 0.0);
        body.put("triggerprice", 0.0);
        body.put("quantityinlot", quantityInLot);
        body.put("disclosedquantity", 0);
        body.put("amoorder", "N");
        body.put("algoid", "");
        body.put("goodtilldate", "");
        body.put("tag", "STOKR");

        try {
            JsonNode root = call(accessToken, "/rest/trans/v2/placeorder", body);
            String message = root.path("message").asText("");
            if ("SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                String orderId = root.path("uniqueorderid").asText(root.path("orderid").asText(null));
                log.info("MOFSL order placed: {} (scrip={}, lots={}) -> {}", request.symbol(), scrip.code(), quantityInLot, orderId);
                return new BrokerOrderResponse(orderId, "OPEN", message);
            }
            log.warn("MOFSL order REJECTED: symbol={} scrip={} side={} lots={} message={}",
                    request.symbol(), scrip.code(), request.side(), quantityInLot, message);
            return new BrokerOrderResponse(null, "REJECTED", message);
        } catch (Exception e) {
            log.error("MOFSL placeOrder failed for {}: {}", request.symbol(), e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("MOFSL: cancelling order {}", orderId);
        try {
            JsonNode root = call(accessToken, "/rest/trans/v1/cancelorder", Map.of("uniqueorderid", orderId));
            if (!"SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                log.warn("MOFSL cancel order {} rejected: {}", orderId, root.path("message").asText());
            }
        } catch (Exception e) {
            log.warn("MOFSL cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("MOFSL: fetching positions");
        try {
            JsonNode root = call(accessToken, "/rest/book/v4/getposition", Map.of());
            if (!"SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                log.warn("MOFSL getPositions failed: {}", root.path("message").asText());
                return Collections.emptyList();
            }
            return parsePositions(root.path("data"));
        } catch (Exception e) {
            log.warn("MOFSL getPositions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    static List<BrokerPosition> parsePositions(JsonNode positions) {
        List<BrokerPosition> result = new ArrayList<>();
        if (positions == null || !positions.isArray()) return result;
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
            result.add(new BrokerPosition(
                    p.path("symbol").asText(""),
                    p.path("exchange").asText("NSEFO"),
                    qty,
                    avgPrice,
                    new BigDecimal(p.path("LTP").asText(p.path("ltp").asText("0"))),
                    new BigDecimal(p.path("marktomarket").asText("0")),
                    new BigDecimal(p.path("bookedprofitloss").asText("0")),
                    p.path("productname").asText(p.path("producttype").asText("NORMAL"))));
        }
        return result;
    }

    // ---- Margin ----

    @Override
    public String lastMarginError() { return lastMarginError; }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("MOFSL: fetching available margin");
        String error;
        try {
            JsonNode root = call(accessToken, "/rest/report/v3/getreportmarginsummary", Map.of());
            if ("SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                Double available = parseAvailableMargin(root.path("data"));
                if (available != null) {
                    log.info("MOFSL: available margin={}", available);
                    lastMarginError = null;
                    return BigDecimal.valueOf(available);
                }
                String body = root.toString();
                error = "margin summary had no available-margin row: "
                        + (body.length() > 300 ? body.substring(0, 300) + "…" : body);
            } else {
                error = root.path("message").asText(root.toString());
            }
        } catch (Exception e) {
            error = e.getMessage();
        }
        log.warn("MOFSL margin fetch failed: {}", error);
        lastMarginError = error;
        return BigDecimal.ZERO;
    }

    /**
     * MO margin summary is a list of {srno, particulars, amount}. Row 103 is the total available margin;
     * otherwise take the row whose particulars read like "Total Available Margin". Flat objects with
     * cashavailable are accepted as a fallback. Null when nothing recognisable is present.
     */
    static Double parseAvailableMargin(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) return null;
        if (data.isArray()) {
            for (JsonNode item : data) {
                if (item.path("srno").asInt(0) == 103) return item.path("amount").asDouble(0);
            }
            for (JsonNode item : data) {
                String p = item.path("particulars").asText("").toLowerCase();
                if (p.contains("total available margin") || (p.contains("available") && p.contains("margin"))) {
                    return item.path("amount").asDouble(0);
                }
            }
            return null;
        }
        for (String f : List.of("cashavailable", "CashAvailable", "availablemargin")) {
            JsonNode v = data.path(f);
            if (!v.isMissingNode() && !v.isNull() && !v.asText("").isBlank()) return v.asDouble(0);
        }
        return null;
    }

    // ---- Order status ----

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        try {
            JsonNode root = call(accessToken, "/rest/book/v5/getorderbook", Map.of());
            JsonNode orders = root.path("data");
            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    String id = o.path("uniqueorderid").asText(o.path("orderid").asText(""));
                    if (orderId.equals(id)) return mapOrderStatus(o.path("orderstatus").asText(null));
                }
            }
        } catch (Exception e) {
            log.warn("MOFSL getOrderStatus {} failed: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }

    /** MO order-book statuses → the platform's OPEN / PARTIAL / COMPLETE / CANCELLED / REJECTED. */
    static String mapOrderStatus(String moStatus) {
        if (moStatus == null || moStatus.isBlank()) return "UNKNOWN";
        String s = moStatus.trim().toLowerCase();
        if (s.contains("partial")) return "PARTIAL";
        if (s.equals("traded") || s.contains("complete") || s.contains("executed") || s.contains("filled")) return "COMPLETE";
        if (s.contains("cancel")) return "CANCELLED";
        if (s.contains("reject") || s.equals("error")) return "REJECTED";
        if (s.contains("confirm") || s.contains("sent") || s.contains("open") || s.contains("pending")
                || s.contains("modif") || s.contains("trigger")) return "OPEN";
        return moStatus.toUpperCase();
    }

    // ---- HTTP ----

    private String mofslPost(String path, Map<String, Object> body, String token,
                             String apiKey, String apiSecret, String clientCode) throws Exception {
        String bodyJson = MAPPER.writeValueAsString(body);
        // MO API only accepts calls from the static IP registered on the app (the Contabo server).
        String serverIp = System.getProperty("server.public-ip", "173.249.55.84");
        String vendorVal = (clientCode != null && !clientCode.isBlank()) ? clientCode.toUpperCase() : "";

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(MOFSL_BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("ApiKey", apiKey != null ? apiKey : "")
                .header("SourceId", "WEB")
                .header("vendorinfo", vendorVal)
                .header("User-Agent", "MOSL/V.1.1.0")
                .header("ClientLocalIp", serverIp)
                .header("ClientPublicIp", serverIp)
                .header("MacAddress", "00:00:00:00:00:00")
                .header("osname", "WEB")
                .header("osversion", "1.0.0")
                .header("devicemodel", "WEB")
                .header("manufacturer", "WEB")
                .header("productname", "STOKR")
                .header("productversion", "1.0.0")
                .header("browsername", "Chrome")
                .header("browserversion", "120.0.0");
        if (apiSecret != null && !apiSecret.isBlank()) req.header("apisecretkey", apiSecret);
        if (token != null && !token.isBlank()) req.header("Authorization", token);

        log.info("MOFSL HTTP: {} vendorinfo={} hasToken={}", path, vendorVal, token != null && !token.isBlank());
        HttpResponse<String> response = httpClient.send(
                req.POST(HttpRequest.BodyPublishers.ofString(bodyJson)).build(), HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        log.info("MOFSL HTTP response: {} status={} body={}", path, response.statusCode(),
                responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
        return responseBody;
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed", e);
        }
    }
}
