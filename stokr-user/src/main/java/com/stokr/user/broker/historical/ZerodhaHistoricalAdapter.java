package com.stokr.user.broker.historical;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.user.broker.ZerodhaKiteApiClient;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaHistoricalAdapter implements BrokerHistoricalDataAdapter {

    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final PlatformBrokerFeedSessionRepository platformSessionRepository;
    private final FieldCipher fieldCipher;
    private final ZerodhaKiteApiClient kiteApiClient;

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
            PlatformBrokerFeedSession session = platformSessionRepository
                    .findByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA")
                    .orElse(null);
            if (session == null || session.getAccessTokenEnc() == null || session.getAccessTokenEnc().isBlank()) {
                return HistoricalFetchResult.fail("NO_PLATFORM_SESSION", "Platform Zerodha session/access token missing");
            }
            String accessToken = fieldCipher.decrypt(session.getAccessTokenEnc());
            if (accessToken == null || accessToken.isBlank()) {
                return HistoricalFetchResult.fail("TOKEN_DECRYPT_FAILED", "Could not decrypt platform access token");
            }

            TokenResolution tokenResolution = resolveInstrumentToken(
                    zerodhaBrokerProperties.getApiKey(),
                    accessToken,
                    request.symbol()
            );
            if (tokenResolution.errorCode() != null) {
                return HistoricalFetchResult.fail(tokenResolution.errorCode(), tokenResolution.errorDetail());
            }
            long instrumentToken = tokenResolution.instrumentToken();
            if (instrumentToken <= 0L) {
                return HistoricalFetchResult.fail("SYMBOL_NOT_MAPPED", "Could not resolve instrument token for " + request.symbol());
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
                return HistoricalFetchResult.fail("BROKER_RESPONSE_INVALID", detail);
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
            return HistoricalFetchResult.ok(out);
        } catch (RestClientResponseException ex) {
            return mapHttpFailure(request.symbol(), ex);
        } catch (Exception ex) {
            log.warn("zerodha.historical.fetch_failed symbol={} {}", request.symbol(), ex.toString());
            return HistoricalFetchResult.fail("BROKER_FETCH_FAILED", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private HistoricalFetchResult mapHttpFailure(String symbol, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String body = ex.getResponseBodyAsString();
        String detail = status + " " + safeText(body, ex.getMessage());
        String upper = (detail == null ? "" : detail.toUpperCase(Locale.ROOT));
        log.warn("zerodha.historical.http_failed symbol={} status={} body={}", symbol, status, safeText(body, ex.getMessage()));
        if (status == 401 || status == 403 || upper.contains("TOKEN") || upper.contains("AUTH")) {
            return HistoricalFetchResult.fail("TOKEN_INVALID", detail);
        }
        if (status == 429 || upper.contains("RATE")) {
            return HistoricalFetchResult.fail("RATE_LIMITED", detail);
        }
        if (status >= 500) {
            return HistoricalFetchResult.fail("BROKER_5XX", detail);
        }
        return HistoricalFetchResult.fail("BROKER_FETCH_FAILED", detail);
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
                String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, exchange);
                loadedAnyMaster = true;
                long tok = parseInstrumentToken(csv, target);
                if (tok > 0L) {
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

    private static long parseInstrumentToken(String csv, String symbolUpper) {
        if (csv == null || csv.isBlank()) {
            return -1L;
        }
        String[] lines = csv.split("\\R");
        if (lines.length < 2) {
            return -1L;
        }
        String[] hdr = splitCsvLine(lines[0]);
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < hdr.length; i++) {
            idx.put(hdr[i].trim().toLowerCase(Locale.ROOT), i);
        }
        Integer tokIdx = idx.get("instrument_token");
        Integer symIdx = idx.get("tradingsymbol");
        if (tokIdx == null || symIdx == null) {
            return -1L;
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
            if (!symbolUpper.equals(sym)) {
                continue;
            }
            try {
                return Long.parseLong(p[tokIdx].trim());
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
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
}
