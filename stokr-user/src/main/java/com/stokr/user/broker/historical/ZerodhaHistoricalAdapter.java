package com.stokr.user.broker.historical;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.user.broker.ZerodhaKiteApiClient;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaHistoricalAdapter implements BrokerHistoricalDataAdapter {

    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final PlatformBrokerFeedSessionRepository platformSessionRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final FieldCipher fieldCipher;
    private final ZerodhaKiteApiClient kiteApiClient;
    private final Map<String, InstrumentCacheRow> instrumentCacheByExchange = new ConcurrentHashMap<>();
    private final Map<String, Instant> validatedTokenCache = new ConcurrentHashMap<>();
    private static final long INSTRUMENT_CACHE_TTL_SECONDS = 600L;
    private static final long TOKEN_VALIDATION_CACHE_TTL_SECONDS = 60L;
    private static final Pattern JSON_MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public String brokerCode() {
        return "ZERODHA";
    }

    @Override
    public BrokerHistoricalCapability capability() {
        return new BrokerHistoricalCapability(
                true,
                List.of("1m", "5m", "15m", "1h", "1d"),
                3650,
                120,
                "IMPLEMENTED",
                "Uses platform feed OAuth session and Kite historical endpoint"
        );
    }

    @Override
    public HistoricalFetchResult fetch(HistoricalFetchRequest request) {
        try {
            if (!zerodhaBrokerProperties.isConfigured()) {
                return HistoricalFetchResult.fail("BROKER_NOT_CONFIGURED", "Zerodha API key/secret missing");
            }
            HistoricalFetchResult lookbackGate = validateLookbackWindow(request);
            if (lookbackGate != null) {
                return lookbackGate;
            }
            AccessTokenResolution accessResolution = resolveAccessToken();
            if (!accessResolution.ok()) {
                return HistoricalFetchResult.failWithSource(accessResolution.code(), accessResolution.detail(), accessResolution.source());
            }
            String accessToken = accessResolution.accessToken();
            String authSource = accessResolution.source();
            String tokenKey = zerodhaBrokerProperties.getApiKey() + ":" + accessToken;
            Instant validatedAt = validatedTokenCache.get(tokenKey);
            if (validatedAt == null || Duration.between(validatedAt, Instant.now()).getSeconds() >= TOKEN_VALIDATION_CACHE_TTL_SECONDS) {
                // Validate the exact platform OAuth context before expensive symbol/chunk flow.
                JsonNode profile = kiteApiClient.getProfile(zerodhaBrokerProperties.getApiKey(), accessToken);
                if (!"success".equalsIgnoreCase(profile.path("status").asText())) {
                    String detail = safeText(profile.path("message").asText(null), "Profile validation failed");
                    return HistoricalFetchResult.failWithSource("TOKEN_INVALID", detail, authSource);
                }
                validatedTokenCache.put(tokenKey, Instant.now());
            }

            TokenResolution tokenResolution = resolveInstrumentToken(
                    zerodhaBrokerProperties.getApiKey(),
                    accessToken,
                    request.symbol()
            );
            if (tokenResolution.errorCode() != null) {
                return HistoricalFetchResult.failWithSource(tokenResolution.errorCode(), tokenResolution.errorDetail(), authSource);
            }
            long instrumentToken = tokenResolution.instrumentToken();
            if (instrumentToken <= 0L) {
                return HistoricalFetchResult.failWithSource("SYMBOL_NOT_MAPPED", "Could not resolve instrument token for " + request.symbol(), authSource);
            }

            String interval = mapInterval(request.timeframe());
            JsonNode root = kiteApiClient.getHistoricalCandles(
                    zerodhaBrokerProperties.getApiKey(),
                    accessToken,
                    instrumentToken,
                    interval,
                    request.rangeStart(),
                    request.rangeEnd()
            );
            JsonNode arr = root.path("data").path("candles");
            if (!arr.isArray()) {
                String detail = root.path("message").asText("Unexpected broker response shape");
                return HistoricalFetchResult.failWithSource("BROKER_RESPONSE_INVALID", detail, authSource);
            }

            List<HistoricalCandlePoint> out = new ArrayList<>();
            for (JsonNode row : arr) {
                if (!row.isArray() || row.size() < 5) {
                    continue;
                }
                Instant openTime = parseTime(row.get(0).asText(null));
                if (openTime == null) {
                    continue;
                }
                BigDecimal open = bd(row.get(1));
                BigDecimal high = bd(row.get(2));
                BigDecimal low = bd(row.get(3));
                BigDecimal close = bd(row.get(4));
                BigDecimal volume = row.size() >= 6 ? bd(row.get(5)) : BigDecimal.ZERO;
                out.add(new HistoricalCandlePoint(openTime, open, high, low, close, volume));
            }
            return HistoricalFetchResult.okWithSource(out, authSource);
        } catch (RestClientResponseException ex) {
            return mapHttpFailure(request.symbol(), ex);
        } catch (ResourceAccessException ex) {
            String msg = ex.getMessage() == null ? "resource access error" : ex.getMessage();
            String code = isTimeoutMessage(msg, ex) ? "BROKER_TIMEOUT" : "BROKER_NETWORK_ERROR";
            return HistoricalFetchResult.fail(code, msg);
        } catch (RestClientException ex) {
            String msg = ex.getMessage() == null ? "rest client error" : ex.getMessage();
            return HistoricalFetchResult.fail("BROKER_CLIENT_ERROR", msg);
        } catch (Exception ex) {
            log.warn("zerodha.historical.fetch_failed symbol={} {}", request.symbol(), ex.toString());
            String msg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            if (isTimeoutMessage(msg, ex)) {
                return HistoricalFetchResult.fail("BROKER_TIMEOUT", msg);
            }
            if (msg.toUpperCase(Locale.ROOT).contains("JSON") || msg.toUpperCase(Locale.ROOT).contains("PARSE")) {
                return HistoricalFetchResult.fail("BROKER_PAYLOAD_PARSE_FAILED", msg);
            }
            return HistoricalFetchResult.fail("BROKER_FETCH_FAILED", msg);
        }
    }

    private HistoricalFetchResult mapHttpFailure(String symbol, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String body = ex.getResponseBodyAsString();
        String detail = status + " " + safeText(body, ex.getMessage());
        String brokerMsg = extractBrokerMessage(body).toUpperCase(Locale.ROOT);
        String upper = (detail == null ? "" : detail.toUpperCase(Locale.ROOT));
        log.warn("zerodha.historical.http_failed symbol={} status={} body={}", symbol, status, safeText(body, ex.getMessage()));
        if (brokerMsg.contains("INVALID FROM DATE") || brokerMsg.contains("INVALID TO DATE")) {
            return HistoricalFetchResult.fail(
                    "LOOKBACK_EXCEEDED",
                    "Requested range is outside Zerodha historical window for this timeframe. Use a recent range (<= 60 days) or import historical data."
            );
        }
        if (status == 401 || status == 403 || upper.contains("TOKEN") || upper.contains("AUTH")) {
            return HistoricalFetchResult.fail("TOKEN_INVALID", detail);
        }
        if (status == 429 || upper.contains("RATE")) {
            return HistoricalFetchResult.fail("RATE_LIMITED", detail);
        }
        if (status >= 500) {
            return HistoricalFetchResult.fail("BROKER_5XX", detail);
        }
        if (status >= 400) {
            return HistoricalFetchResult.fail("BROKER_REQUEST_INVALID", detail);
        }
        return HistoricalFetchResult.fail("BROKER_FETCH_FAILED", detail);
    }

    private static String extractBrokerMessage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        Matcher m = JSON_MESSAGE_PATTERN.matcher(rawBody);
        if (m.find()) {
            return m.group(1);
        }
        return rawBody;
    }

    private static String safeText(String body, String fallback) {
        if (body != null && !body.isBlank()) {
            String trimmed = body.replaceAll("\\s+", " ").trim();
            return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
        }
        return fallback == null ? "unknown" : fallback;
    }

    private TokenResolution resolveInstrumentToken(String apiKey, String accessToken, String symbol) {
        String target = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (target.isBlank()) {
            return new TokenResolution(-1L, "SYMBOL_NOT_MAPPED", "Blank symbol");
        }
        boolean loadedAnyMaster = false;
        String lastErrorCode = null;
        String lastErrorDetail = null;
        for (String exchange : List.of("NSE", "NFO")) {
            try {
                Map<String, Long> tokenMap = loadInstrumentTokenMap(apiKey, accessToken, exchange);
                loadedAnyMaster = true;
                Long tok = tokenMap.get(target);
                if (tok != null && tok > 0L) {
                    return new TokenResolution(tok, null, null);
                }
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                String body = ex.getResponseBodyAsString();
                String detail = "instrument master " + exchange + " " + status + " " + safeText(body, ex.getMessage());
                if (status == 401 || status == 403) {
                    lastErrorCode = "TOKEN_INVALID";
                    lastErrorDetail = detail;
                } else if (status == 429) {
                    lastErrorCode = "RATE_LIMITED";
                    lastErrorDetail = detail;
                } else if (status >= 500) {
                    lastErrorCode = "BROKER_5XX";
                    lastErrorDetail = detail;
                } else {
                    lastErrorCode = "INSTRUMENT_MASTER_UNAVAILABLE";
                    lastErrorDetail = detail;
                }
            } catch (Exception ex) {
                lastErrorCode = "INSTRUMENT_MASTER_UNAVAILABLE";
                lastErrorDetail = "instrument master " + exchange + " " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.debug("zerodha.historical.instruments_parse exchange={} {}", exchange, ex.toString());
            }
        }
        if (!loadedAnyMaster && lastErrorCode != null) {
            return new TokenResolution(-1L, lastErrorCode, lastErrorDetail);
        }
        return new TokenResolution(-1L, null, null);
    }

    private Map<String, Long> loadInstrumentTokenMap(String apiKey, String accessToken, String exchange) {
        String ex = exchange == null ? "NSE" : exchange.trim().toUpperCase(Locale.ROOT);
        InstrumentCacheRow cached = instrumentCacheByExchange.get(ex);
        if (cached != null && Duration.between(cached.loadedAt(), Instant.now()).getSeconds() < INSTRUMENT_CACHE_TTL_SECONDS) {
            return cached.tokenBySymbol();
        }
        String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, ex);
        Map<String, Long> parsed = parseInstrumentTokenMap(csv);
        instrumentCacheByExchange.put(ex, new InstrumentCacheRow(parsed, Instant.now()));
        return parsed;
    }

    private static Map<String, Long> parseInstrumentTokenMap(String csv) {
        Map<String, Long> out = new HashMap<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        String[] lines = csv.split("\\R");
        if (lines.length < 2) {
            return out;
        }
        String[] hdr = splitCsvLine(lines[0]);
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < hdr.length; i++) {
            idx.put(hdr[i].trim().toLowerCase(Locale.ROOT), i);
        }
        Integer tokIdx = idx.get("instrument_token");
        Integer symIdx = idx.get("tradingsymbol");
        if (tokIdx == null || symIdx == null) {
            return out;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] p = splitCsvLine(line);
            if (p.length <= Math.max(tokIdx, symIdx)) {
                continue;
            }
            String sym = p[symIdx].trim().toUpperCase(Locale.ROOT);
            if (sym.isBlank()) {
                continue;
            }
            try {
                long tok = Long.parseLong(p[tokIdx].trim());
                if (tok > 0L) {
                    out.put(sym, tok);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static boolean isTimeoutMessage(String msg, Throwable ex) {
        String m = msg == null ? "" : msg.toUpperCase(Locale.ROOT);
        if (m.contains("TIMEOUT") || m.contains("TIMED OUT") || m.contains("READ TIMED OUT") || m.contains("CONNECT TIMED OUT")) {
            return true;
        }
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String mapInterval(String timeframe) {
        String tf = timeframe == null ? "1m" : timeframe.trim().toLowerCase(Locale.ROOT);
        return switch (tf) {
            case "1m" -> "minute";
            case "5m" -> "5minute";
            case "15m" -> "15minute";
            case "1h" -> "60minute";
            case "1d" -> "day";
            default -> "minute";
        };
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception ex) {
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static BigDecimal bd(JsonNode n) {
        if (n == null || n.isNull()) {
            return BigDecimal.ZERO;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        try {
            return new BigDecimal(n.asText("0"));
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private record TokenResolution(long instrumentToken, String errorCode, String errorDetail) {
    }

    private HistoricalFetchResult validateLookbackWindow(HistoricalFetchRequest request) {
        String tf = request.timeframe() == null ? "1m" : request.timeframe().trim().toLowerCase(Locale.ROOT);
        long maxDays = switch (tf) {
            case "1d" -> 3650L;
            case "1h", "15m", "5m", "1m" -> 60L;
            default -> 60L;
        };
        Instant cutoff = Instant.now().minus(Duration.ofDays(maxDays));
        if (request.rangeStart().isBefore(cutoff)) {
            return HistoricalFetchResult.fail(
                    "LOOKBACK_EXCEEDED",
                    "Requested range exceeds Zerodha historical lookback for " + tf
                            + " (max " + maxDays + " days). Choose a recent range or use import/bootstrap."
            );
        }
        return null;
    }

    private record InstrumentCacheRow(Map<String, Long> tokenBySymbol, Instant loadedAt) {
    }

    private AccessTokenResolution resolveAccessToken() {
        PlatformBrokerFeedSession platform = platformSessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc("ZERODHA")
                .orElse(null);
        if (platform != null && platform.getAccessTokenEnc() != null && !platform.getAccessTokenEnc().isBlank()) {
            String token = safeDecrypt(platform.getAccessTokenEnc());
            if (token != null && !token.isBlank()) {
                return new AccessTokenResolution(token, "PLATFORM_SESSION", true, null, null);
            }
        }
        BrokerAccount trader = brokerAccountRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseAndStatusIgnoreCaseAndAccessTokenEncIsNotNullOrderByUpdatedAtDesc("ZERODHA", "CONNECTED")
                .orElse(null);
        if (trader != null && trader.getAccessTokenEnc() != null && !trader.getAccessTokenEnc().isBlank()) {
            String token = safeDecrypt(trader.getAccessTokenEnc());
            if (token != null && !token.isBlank()) {
                log.warn("zerodha.historical.using_trader_token_fallback brokerUserId={}", trader.getBrokerUserId());
                return new AccessTokenResolution(token, "TRADER_ACCOUNT_FALLBACK", true, null, null);
            }
        }
        return new AccessTokenResolution(null, "NONE", false, "NO_PLATFORM_SESSION", "No usable Zerodha token (platform/trader)");
    }

    private String safeDecrypt(String enc) {
        try {
            return fieldCipher.decrypt(enc);
        } catch (Exception ex) {
            return null;
        }
    }

    private record AccessTokenResolution(
            String accessToken,
            String source,
            boolean ok,
            String code,
            String detail
    ) {
    }
}
