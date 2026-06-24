package com.stokr.external;

import com.stokr.engine.CandleData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaCandleService {

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    @Value("${broker.zerodha.api-secret:}")
    private String zerodhaApiSecret;

    private static final String KITE_API_BASE = "https://api.kite.trade";
    private static final Map<String, String> SYMBOL_TO_TOKEN = new HashMap<>();

    static {
        // NIFTY 50
        SYMBOL_TO_TOKEN.put("RELIANCE",   "738561");
        SYMBOL_TO_TOKEN.put("TCS",        "2953217");
        SYMBOL_TO_TOKEN.put("HDFCBANK",   "341249");
        SYMBOL_TO_TOKEN.put("ICICIBANK",  "1270529");
        SYMBOL_TO_TOKEN.put("INFY",       "408065");
        SYMBOL_TO_TOKEN.put("HINDUNILVR", "356865");
        SYMBOL_TO_TOKEN.put("ITC",        "424961");
        SYMBOL_TO_TOKEN.put("KOTAKBANK",  "492033");
        SYMBOL_TO_TOKEN.put("LT",         "2939649");
        SYMBOL_TO_TOKEN.put("SBIN",       "779521");
        SYMBOL_TO_TOKEN.put("AXISBANK",   "1510401");
        SYMBOL_TO_TOKEN.put("BAJFINANCE", "225537");
        SYMBOL_TO_TOKEN.put("BHARTIARTL", "2714625");
        SYMBOL_TO_TOKEN.put("TITAN",      "897537");
        SYMBOL_TO_TOKEN.put("MARUTI",     "2815745");
        SYMBOL_TO_TOKEN.put("HCLTECH",    "1850625");
        SYMBOL_TO_TOKEN.put("SUNPHARMA",  "857857");
        SYMBOL_TO_TOKEN.put("TATAMOTORS", "884737");
        SYMBOL_TO_TOKEN.put("NTPC",       "2977281");
        SYMBOL_TO_TOKEN.put("BAJAJFINSV", "54273");
        SYMBOL_TO_TOKEN.put("WIPRO",      "969473");
        SYMBOL_TO_TOKEN.put("JSWSTEEL",   "3001089");
        SYMBOL_TO_TOKEN.put("ONGC",       "633601");
        SYMBOL_TO_TOKEN.put("POWERGRID",  "3834113");
        SYMBOL_TO_TOKEN.put("COALINDIA",  "5215745");
        SYMBOL_TO_TOKEN.put("GRASIM",     "315393");
        SYMBOL_TO_TOKEN.put("TATASTEEL",  "895745");
        SYMBOL_TO_TOKEN.put("BPCL",       "134657");
        SYMBOL_TO_TOKEN.put("HINDALCO",   "348929");
        SYMBOL_TO_TOKEN.put("ULTRACEMCO", "2952193");
        SYMBOL_TO_TOKEN.put("ADANIENT",   "6401");
        SYMBOL_TO_TOKEN.put("ADANIPORTS", "15083777");
        SYMBOL_TO_TOKEN.put("APOLLOHOSP", "41729");
        SYMBOL_TO_TOKEN.put("DIVISLAB",   "2800641");
        SYMBOL_TO_TOKEN.put("DRREDDY",    "225537");
        SYMBOL_TO_TOKEN.put("EICHERMOT",  "232961");
        SYMBOL_TO_TOKEN.put("HDFCLIFE",   "119173121");
        SYMBOL_TO_TOKEN.put("HEROMOTOCO", "345089");
        SYMBOL_TO_TOKEN.put("INDUSINDBK", "1346049");
        SYMBOL_TO_TOKEN.put("M&M",        "519937");
        SYMBOL_TO_TOKEN.put("NESTLEIND",  "4598529");
        SYMBOL_TO_TOKEN.put("SBILIFE",    "5582849");
        SYMBOL_TO_TOKEN.put("TATACONSUM", "878593");
        SYMBOL_TO_TOKEN.put("TECHM",      "3465729");
        SYMBOL_TO_TOKEN.put("TRENT",      "2368513");
        SYMBOL_TO_TOKEN.put("DMART",      "4451329");
        SYMBOL_TO_TOKEN.put("UPL",        "2889473");
        SYMBOL_TO_TOKEN.put("CIPLA",      "177409");
        SYMBOL_TO_TOKEN.put("BRITANNIA",  "140033");
        SYMBOL_TO_TOKEN.put("ASIANPAINT", "60417");
        // NIFTY Next 50
        SYMBOL_TO_TOKEN.put("BERGEPAINT", "70401");
        SYMBOL_TO_TOKEN.put("CANBK",      "151041");
        SYMBOL_TO_TOKEN.put("DABUR",      "185409");
        SYMBOL_TO_TOKEN.put("GODREJCP",   "2672641");
        SYMBOL_TO_TOKEN.put("HAL",        "12455425");
        SYMBOL_TO_TOKEN.put("HAVELLS",    "14870273");
        SYMBOL_TO_TOKEN.put("HDFCAMC",    "119076869");
        SYMBOL_TO_TOKEN.put("IOB",        "2030849");
        SYMBOL_TO_TOKEN.put("IRCTC",      "3502338");
        SYMBOL_TO_TOKEN.put("LICI",       "4633601");
        SYMBOL_TO_TOKEN.put("MCDOWELL",   "547209");
        SYMBOL_TO_TOKEN.put("PIDILITIND", "680705");
        SYMBOL_TO_TOKEN.put("POLYCAB",    "3455489");
        SYMBOL_TO_TOKEN.put("SIEMENS",    "806401");
        SYMBOL_TO_TOKEN.put("ZOMATO",     "5215745");
        SYMBOL_TO_TOKEN.put("AMBUJACEM",  "17921");
        SYMBOL_TO_TOKEN.put("ATGL",       "10604289");
        SYMBOL_TO_TOKEN.put("BANDHANBNK", "579137");
        SYMBOL_TO_TOKEN.put("BANKBARODA", "1195009");
        SYMBOL_TO_TOKEN.put("BEL",        "87041");
        SYMBOL_TO_TOKEN.put("CHOLAFIN",   "175361");
        SYMBOL_TO_TOKEN.put("COFORGE",    "1691137");
        SYMBOL_TO_TOKEN.put("COLPAL",     "177665");
        SYMBOL_TO_TOKEN.put("DALBHARAT",  "192537");
        SYMBOL_TO_TOKEN.put("FEDERALBNK", "261889");
        SYMBOL_TO_TOKEN.put("GAIL",       "1207553");
        SYMBOL_TO_TOKEN.put("GODREJPROP", "3721473");
        SYMBOL_TO_TOKEN.put("IDFCFIRSTB", "3488001");
        SYMBOL_TO_TOKEN.put("INDUSTOWER", "10694145");
        SYMBOL_TO_TOKEN.put("IRFC",       "3679745");
        SYMBOL_TO_TOKEN.put("JUBLFOOD",   "1977345");
        SYMBOL_TO_TOKEN.put("KALYANKJIL", "5337089");
        SYMBOL_TO_TOKEN.put("LALPATHLAB", "6386177");
        SYMBOL_TO_TOKEN.put("LODHA",      "4949249");
        SYMBOL_TO_TOKEN.put("LTTS",       "4561153");
        SYMBOL_TO_TOKEN.put("LUPIN",      "2672641");
        SYMBOL_TO_TOKEN.put("MFSL",       "3675393");
        SYMBOL_TO_TOKEN.put("NHPC",       "820225");
        SYMBOL_TO_TOKEN.put("NYKAA",      "5215745");
        SYMBOL_TO_TOKEN.put("OFSS",       "621569");
        SYMBOL_TO_TOKEN.put("PAGEIND",    "630785");
        SYMBOL_TO_TOKEN.put("PAYTM",      "5215745");
        SYMBOL_TO_TOKEN.put("PERSISTENT", "4254465");
        SYMBOL_TO_TOKEN.put("RECLTD",     "738177");
        SYMBOL_TO_TOKEN.put("SAIL",       "758529");
        SYMBOL_TO_TOKEN.put("SHREECEM",   "794753");
        SYMBOL_TO_TOKEN.put("TORNTPHARM", "900609");
        SYMBOL_TO_TOKEN.put("TVSMOTOR",   "2170625");
        SYMBOL_TO_TOKEN.put("VBL",        "5215745");
        SYMBOL_TO_TOKEN.put("VEDL",       "952321");
        SYMBOL_TO_TOKEN.put("VOLTAS",     "951809");
    }

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();

    public List<CandleData> fetchCandles(String symbol, String timeframe, Instant startTime, Instant endTime) {
        log.info("Fetching candles from Zerodha: symbol={}, timeframe={}, start={}, end={}",
            symbol, timeframe, startTime, endTime);

        if (!tokenManager.isAuthenticated()) {
            log.warn("Zerodha not authenticated - fetch candles requires OAuth login via browser");
            return Collections.emptyList();
        }

        try {
            String token = SYMBOL_TO_TOKEN.get(symbol.toUpperCase());
            if (token == null) {
                log.warn("No instrument token mapping for symbol: {}", symbol);
                return Collections.emptyList();
            }

            String interval = mapInterval(timeframe);
            ZonedDateTime istStart = startTime.atZone(ZoneId.of("Asia/Kolkata"));
            ZonedDateTime istEnd = endTime.atZone(ZoneId.of("Asia/Kolkata"));
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String from = istStart.format(fmt);
            String to = istEnd.format(fmt);

            String url = String.format("%s/instruments/historical/%s/%s?from=%s&to=%s",
                KITE_API_BASE, token, interval, from, to);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Authorization", "token " + zerodhaApiKey + ":" + tokenManager.getCurrentAuth().getAccessToken());

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();

            return parseKiteResponse(response, symbol, timeframe);

        } catch (Exception e) {
            log.error("Failed to fetch candles from Zerodha: {}", e.getMessage());
            if (tokenManager.refreshToken()) {
                log.info("Token refreshed, retrying candle fetch...");
                return retryFetch(symbol, timeframe, startTime, endTime);
            }
            return Collections.emptyList();
        }
    }

    private List<CandleData> retryFetch(String symbol, String timeframe, Instant startTime, Instant endTime) {
        try {
            String token = SYMBOL_TO_TOKEN.get(symbol.toUpperCase());
            if (token == null) return Collections.emptyList();

            String interval = mapInterval(timeframe);
            ZonedDateTime istStart = startTime.atZone(ZoneId.of("Asia/Kolkata"));
            ZonedDateTime istEnd = endTime.atZone(ZoneId.of("Asia/Kolkata"));
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String url = String.format("%s/instruments/historical/%s/%s?from=%s&to=%s",
                KITE_API_BASE, token, interval, istStart.format(fmt), istEnd.format(fmt));

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Authorization", "token " + zerodhaApiKey + ":" + tokenManager.getCurrentAuth().getAccessToken());

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
            return parseKiteResponse(response, symbol, timeframe);

        } catch (Exception e) {
            log.error("Retry failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // Kite API candle format: ["2026-04-25T09:15:00+0530", open, high, low, close, volume]
    private static final java.time.format.DateTimeFormatter KITE_TS_FMT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final java.time.format.DateTimeFormatter KITE_TS_FMT2 =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private List<CandleData> parseKiteResponse(String json, String symbol, String timeframe) {
        List<CandleData> candles = new ArrayList<>();
        try {
            String candlesKey = "\"candles\":[";
            int start = json.indexOf(candlesKey);
            if (start < 0) {
                log.warn("No candles in Kite response for {} — raw: {}", symbol, json.length() > 200 ? json.substring(0, 200) : json);
                return candles;
            }
            start += candlesKey.length();
            int end = json.lastIndexOf("]]");
            if (end < 0) return candles;
            end += 1; // include first ]

            String candlesStr = json.substring(start, end);
            if (candlesStr.isBlank() || candlesStr.equals("null")) return candles;

            int depth = 0;
            int bufStart = -1;
            for (int i = 0; i < candlesStr.length(); i++) {
                char c = candlesStr.charAt(i);
                if (c == '[') {
                    if (depth == 0) bufStart = i + 1;
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0 && bufStart >= 0) {
                        String row = candlesStr.substring(bufStart, i);
                        // Split on comma but not inside quotes
                        String[] parts = row.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                        if (parts.length >= 6) {
                            try {
                                String tsRaw = parts[0].trim().replace("\"", "");
                                Instant ts;
                                try {
                                    ts = java.time.ZonedDateTime.parse(tsRaw, KITE_TS_FMT).toInstant();
                                } catch (Exception e1) {
                                    ts = java.time.LocalDateTime.parse(tsRaw, KITE_TS_FMT2)
                                        .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
                                }
                                CandleData candle = new CandleData();
                                candle.setSymbol(symbol);
                                candle.setTimeframe(timeframe);
                                candle.setTimestamp(ts);
                                candle.setOpen(new BigDecimal(parts[1].trim()));
                                candle.setHigh(new BigDecimal(parts[2].trim()));
                                candle.setLow(new BigDecimal(parts[3].trim()));
                                candle.setClose(new BigDecimal(parts[4].trim()));
                                candle.setVolume((long) Double.parseDouble(parts[5].trim()));
                                candles.add(candle);
                            } catch (Exception e) {
                                log.warn("Failed to parse candle row [{}]: {}", row, e.getMessage());
                            }
                        }
                        bufStart = -1;
                    }
                }
            }
            log.info("Parsed {} candles from Kite API for {}", candles.size(), symbol);
        } catch (Exception e) {
            log.error("Failed to parse Kite response for {}: {}", symbol, e.getMessage());
        }
        return candles;
    }

    private String mapInterval(String timeframe) {
        return switch (timeframe) {
            case "1min" -> "minute";
            case "5min" -> "5minute";
            case "15min" -> "15minute";
            case "hourly" -> "60minute";
            case "daily" -> "day";
            case "weekly" -> "week";
            case "monthly" -> "month";
            default -> "day";
        };
    }

    public boolean authenticate(String requestToken) {
        try {
            log.info("Authenticating with Zerodha using request token...");
            String checksum = sha256Hex(zerodhaApiKey + requestToken + zerodhaApiSecret);
            String url = String.format("%s/session/token", KITE_API_BASE);
            String body = String.format("api_key=%s&request_token=%s&checksum=%s",
                zerodhaApiKey, requestToken, checksum);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Content-Type", "application/x-www-form-urlencoded");

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            String response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class).getBody();

            if (response != null && response.contains("\"access_token\"")) {
                String accessToken = extractValue(response, "access_token");
                String refreshToken = extractValue(response, "refresh_token");
                int expiresIn = 86400;
                String expiresStr = extractValue(response, "login_time");
                if (expiresStr != null) {
                    try {
                        Instant loginTime = Instant.parse(expiresStr);
                        expiresIn = (int) Duration.between(loginTime, loginTime.plus(24, ChronoUnit.HOURS)).getSeconds();
                    } catch (Exception ignored) {}
                }
                tokenManager.setAuth(accessToken, refreshToken, expiresIn);
                log.info("Zerodha authentication successful");
                return true;
            }
            log.warn("Zerodha authentication failed - unexpected response");
            return false;

        } catch (Exception e) {
            log.error("Zerodha authentication failed: {}", e.getMessage());
            return false;
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private String extractValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }
}
