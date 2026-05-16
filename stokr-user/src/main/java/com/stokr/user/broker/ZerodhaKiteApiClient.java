package com.stokr.user.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Kite Connect REST client (session token auth).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaKiteApiClient {

    private static final String BASE = "https://api.kite.trade";

    private final ObjectMapper objectMapper;
    private final RestClient http = RestClient.builder().build();

    private HttpHeaders authHeaders(String apiKey, String accessToken) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.AUTHORIZATION, "token " + apiKey + ":" + accessToken);
        return h;
    }

    public JsonNode getProfile(String apiKey, String accessToken) {
        String body = http.get()
                .uri(BASE + "/user/profile")
                .headers(h -> h.addAll(authHeaders(apiKey, accessToken)))
                .retrieve()
                .body(String.class);
        return readJson(body);
    }

    public JsonNode getMargins(String apiKey, String accessToken) {
        String body = http.get()
                .uri(BASE + "/user/margins")
                .headers(h -> h.addAll(authHeaders(apiKey, accessToken)))
                .retrieve()
                .body(String.class);
        return readJson(body);
    }

    public JsonNode getOrders(String apiKey, String accessToken) {
        String body = http.get()
                .uri(BASE + "/orders")
                .headers(h -> h.addAll(authHeaders(apiKey, accessToken)))
                .retrieve()
                .body(String.class);
        return readJson(body);
    }

    /**
     * Full instrument dump for one exchange (CSV). Used to map instrument_token → tradingsymbol for platform WS ticks.
     */
    public String getInstrumentsCsv(String apiKey, String accessToken, String exchange) {
        String ex = exchange == null || exchange.isBlank() ? "NSE" : exchange.trim().toUpperCase();
        return http.get()
                .uri(BASE + "/instruments/" + ex)
                .headers(h -> h.addAll(authHeaders(apiKey, accessToken)))
                .retrieve()
                .body(String.class);
    }

    /**
     * Historical candles endpoint (Kite): returns raw payload so adapters can map broker-specific schema safely.
     */
    public JsonNode getHistoricalCandles(
            String apiKey,
            String accessToken,
            long instrumentToken,
            String interval,
            Instant fromInclusive,
            Instant toInclusive
    ) {
        String itv = interval == null || interval.isBlank() ? "minute" : interval.trim().toLowerCase();
        String from = URLEncoder.encode(fromInclusive.toString(), StandardCharsets.UTF_8);
        String to = URLEncoder.encode(toInclusive.toString(), StandardCharsets.UTF_8);
        String url = BASE + "/instruments/historical/" + instrumentToken + "/" + itv
                + "?from=" + from
                + "&to=" + to
                + "&oi=0";
        String body = http.get()
                .uri(url)
                .headers(h -> h.addAll(authHeaders(apiKey, accessToken)))
                .retrieve()
                .body(String.class);
        return readJson(body);
    }

    /**
     * Places a regular (exchange) order. Caller must enforce safety limits.
     */
    public JsonNode placeRegularOrder(
            String apiKey,
            String accessToken,
            String variety,
            String exchange,
            String tradingsymbol,
            String transactionType,
            int quantity,
            String orderType,
            String product
    ) {
        String normalizedVariety = variety == null || variety.isBlank() ? "regular" : variety.trim().toLowerCase();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("exchange", exchange);
        fields.put("tradingsymbol", tradingsymbol);
        fields.put("transaction_type", transactionType);
        fields.put("quantity", String.valueOf(quantity));
        fields.put("order_type", orderType);
        fields.put("product", product);
        fields.put("validity", "DAY");
        // Kite rejects MARKET/SL-M via API without market_protection (InputException). -1 = exchange guidelines.
        String ot = orderType == null ? "" : orderType.trim();
        if ("MARKET".equalsIgnoreCase(ot) || "SL-M".equalsIgnoreCase(ot)) {
            fields.put("market_protection", "-1");
        }
        String formBody = encodeForm(fields);

        String body = http.post()
                .uri(BASE + "/orders/" + normalizedVariety)
                .headers(h -> {
                    h.addAll(authHeaders(apiKey, accessToken));
                    h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                })
                .body(formBody)
                .retrieve()
                .body(String.class);
        return readJson(body);
    }

    private static String encodeForm(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            log.warn("kite.json_parse {}", e.getClass().getSimpleName());
            return objectMapper.createObjectNode();
        }
    }
}
