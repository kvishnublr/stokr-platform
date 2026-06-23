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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaCandleService {

    @Value("${zerodha.api.key:}")
    private String zerodhaApiKey;

    private static final String KITE_API_BASE = "https://api.kite.trade";
    private static final Map<String, String> SYMBOL_TO_TOKEN = new HashMap<>();

    static {
        SYMBOL_TO_TOKEN.put("RELIANCE", "779521");
        SYMBOL_TO_TOKEN.put("TCS", "771993");
        SYMBOL_TO_TOKEN.put("INFY", "408065");
        SYMBOL_TO_TOKEN.put("HDFC", "341249");
        SYMBOL_TO_TOKEN.put("HDFCBANK", "341249");
        SYMBOL_TO_TOKEN.put("ICICI", "1270529");
        SYMBOL_TO_TOKEN.put("ICICIBANK", "1270529");
        SYMBOL_TO_TOKEN.put("WIPRO", "969475");
        SYMBOL_TO_TOKEN.put("AXISBANK", "1510401");
        SYMBOL_TO_TOKEN.put("SBIN", "779521");
        SYMBOL_TO_TOKEN.put("HINDUNILVR", "8894465");
        SYMBOL_TO_TOKEN.put("ITC", "1135105");
        SYMBOL_TO_TOKEN.put("BAJFINANCE", "10393601");
        SYMBOL_TO_TOKEN.put("KOTAKBANK", "492033");
        SYMBOL_TO_TOKEN.put("MARUTI", "11650561");
        SYMBOL_TO_TOKEN.put("BHARTIARTL", "2714625");
        SYMBOL_TO_TOKEN.put("DMART", "15023105");
        SYMBOL_TO_TOKEN.put("TITAN", "13798401");
        SYMBOL_TO_TOKEN.put("ASIANPAINT", "4306177");
        SYMBOL_TO_TOKEN.put("NESTLEIND", "12301825");
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

    private List<CandleData> parseKiteResponse(String json, String symbol, String timeframe) {
        List<CandleData> candles = new ArrayList<>();
        try {
            String candlesKey = "\"candles\":[";
            int start = json.indexOf(candlesKey);
            if (start < 0) {
                log.warn("No candles array in Kite response for {}", symbol);
                return candles;
            }
            start += candlesKey.length();
            int end = json.lastIndexOf("]");
            if (end < 0) return candles;

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
                        String[] parts = candlesStr.substring(bufStart, i).split(",");
                        if (parts.length >= 6) {
                            try {
                                long epochMs = Long.parseLong(parts[0].trim());
                                CandleData candle = new CandleData();
                                candle.setSymbol(symbol);
                                candle.setTimeframe(timeframe);
                                candle.setTimestamp(Instant.ofEpochMilli(epochMs));
                                candle.setOpen(new BigDecimal(parts[1].trim()));
                                candle.setHigh(new BigDecimal(parts[2].trim()));
                                candle.setLow(new BigDecimal(parts[3].trim()));
                                candle.setClose(new BigDecimal(parts[4].trim()));
                                candle.setVolume((long) Double.parseDouble(parts[5].trim()));
                                candles.add(candle);
                            } catch (Exception e) {
                                log.warn("Failed to parse candle row: {}", e.getMessage());
                            }
                        }
                        bufStart = -1;
                    }
                }
            }
            log.info("Parsed {} candles from Kite API for {}", candles.size(), symbol);
        } catch (Exception e) {
            log.error("Failed to parse Kite response: {}", e.getMessage());
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

    public boolean authenticate(String requestToken, String secret) {
        try {
            log.info("Authenticating with Zerodha using request token...");
            String url = String.format("%s/session/token", KITE_API_BASE);
            String body = String.format("api_key=%s&request_token=%s&checksum=%s",
                zerodhaApiKey, requestToken, secret);

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

    private String extractValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }
}
