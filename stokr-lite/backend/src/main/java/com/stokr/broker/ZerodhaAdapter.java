package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Component
public class ZerodhaAdapter implements BrokerAdapter {

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @Value("${broker.zerodha.api-secret:}")
    private String apiSecret;

    @Value("${broker.zerodha.redirect-uri:http://localhost:8080/api/brokers/zerodha/callback}")
    private String redirectUri;

    private static final String KITE_AUTH_URL = "https://kite.zerodha.com/connect/login?v=3&api_key=";
    private static final String KITE_API_BASE = "https://api.kite.trade";

    private final RestClient http;

    public ZerodhaAdapter(RestClient.Builder restClientBuilder) {
        this.http = restClientBuilder.build();
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public String getAuthUrl() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Zerodha API key is not configured. Set ZERODHA_API_KEY environment variable.");
        }
        return KITE_AUTH_URL + apiKey;
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("Zerodha API credentials are not configured.");
        }

        String checksum = sha256Hex(apiKey + requestToken + apiSecret);

        log.info("zerodha.token_exchange.start");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("api_key", apiKey);
        form.add("request_token", requestToken);
        form.add("checksum", checksum);

        String responseBody;
        try {
            responseBody = http.post()
                    .uri(KITE_API_BASE + "/session/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            String zerodhaMsg = extractZerodhaMessage(e);
            log.warn("zerodha.token_exchange.failed: {} zerodhaMsg={}", e.getClass().getSimpleName(), zerodhaMsg);
            throw new RuntimeException(
                    zerodhaMsg != null && !zerodhaMsg.isBlank()
                            ? zerodhaMsg
                            : "Could not complete Zerodha login — try again.");
        }

        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody != null ? responseBody : "{}");
            if (!"success".equalsIgnoreCase(root.path("status").asText())) {
                throw new RuntimeException("Zerodha rejected token exchange");
            }
            JsonNode data = root.path("data");
            String accessToken = data.path("access_token").asText(null);
            String refreshToken = data.path("refresh_token").asText(null);
            String userId = data.path("user_id").asText(null);

            if (accessToken == null || accessToken.isBlank()) {
                throw new RuntimeException("Missing access_token from Zerodha");
            }

            log.info("zerodha.token_exchange.success userId={}", userId);
            return new String[]{accessToken, refreshToken != null ? refreshToken : "", userId != null ? userId : ""};
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Zerodha response", e);
        }
    }

    private String extractZerodhaMessage(Exception e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException rce) {
            try {
                JsonNode errBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(rce.getResponseBodyAsString());
                return errBody.path("message").asText(null);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Placing Zerodha order: {} {} qty={} price={}", request.side(), request.symbol(), request.quantity(), request.price());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("tradingsymbol", request.symbol());
        form.add("exchange",       request.exchange() != null ? request.exchange() : "NSE");
        form.add("transaction_type", request.side().name());
        form.add("product",        request.productType() != null ? request.productType() : "MIS");
        form.add("quantity",       String.valueOf(request.quantity()));
        form.add("validity",       "DAY");

        if (request.price() != null && request.price() > 0) {
            double bufferFactor = request.side() == BrokerOrderRequest.Side.BUY ? 1.0015 : 0.9985;
            double limitPrice = Math.round(request.price() * bufferFactor * 100.0) / 100.0;
            form.add("order_type", "LIMIT");
            form.add("price", String.valueOf(limitPrice));
        } else {
            // NSE now rejects bare F&O market orders ("Market orders without market
            // protection are not allowed... use a Limit order"), and Kite Connect has no
            // request field to supply protection like Navia's mkt_protection does -- its
            // own error says to use a Limit order instead. Fetch the current LTP and place
            // an aggressive marketable limit (wider buffer than the explicit-price branch,
            // since this is standing in for "just fill me now" rather than a targeted close).
            double ltp = fetchLtp(accessToken, request.exchange() != null ? request.exchange() : "NFO", request.symbol());
            if (ltp > 0) {
                double bufferFactor = request.side() == BrokerOrderRequest.Side.BUY ? 1.01 : 0.99;
                double limitPrice = Math.round(ltp * bufferFactor * 100.0) / 100.0;
                form.add("order_type", "LIMIT");
                form.add("price", String.valueOf(limitPrice));
            } else {
                form.add("order_type", "MARKET");
                form.add("disclosed_quantity", "0");
            }
        }

        try {
            String body = http.post()
                    .uri(KITE_API_BASE + "/orders/regular")
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if ("success".equalsIgnoreCase(root.path("status").asText())) {
                String orderId = root.path("data").path("order_id").asText();
                log.info("Zerodha order placed: {} {}", request.symbol(), orderId);
                return new BrokerOrderResponse(orderId, "OPEN", "Order placed");
            }
            String msg = root.path("message").asText("Order rejected");
            log.warn("Zerodha order rejected: {}", msg);
            return new BrokerOrderResponse(null, "REJECTED", msg);
        } catch (Exception e) {
            String msg = extractZerodhaMessage(e);
            log.error("Zerodha placeOrder failed for {}: {}", request.symbol(), msg != null ? msg : e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", msg != null ? msg : e.getMessage());
        }
    }

    /**
     * Real lot size per underlying, straight from Zerodha's own instrument dump -- avoids
     * hardcoding numbers that go stale whenever NSE revises them (which already happened:
     * NIFTY's real lot size is 65, not the 25 that used to be hardcoded here). NFO's dump
     * has one row per strike/expiry per underlying; lot size is invariant per underlying at
     * any point in time, so the first row seen per "name" is kept.
     */
    public Map<String, Integer> fetchLotSizes(String accessToken) {
        Map<String, Integer> result = new LinkedHashMap<>();
        try {
            String csv = http.get()
                    .uri(java.net.URI.create(KITE_API_BASE + "/instruments/NFO"))
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);
            if (csv == null || csv.isBlank()) return result;
            String[] lines = csv.split("\n");
            if (lines.length < 2) return result;
            String[] header = lines[0].split(",");
            int nameIdx = -1, lotIdx = -1;
            for (int i = 0; i < header.length; i++) {
                String h = header[i].trim();
                if ("name".equalsIgnoreCase(h)) nameIdx = i;
                if ("lot_size".equalsIgnoreCase(h)) lotIdx = i;
            }
            if (nameIdx < 0 || lotIdx < 0) {
                log.warn("Zerodha instruments CSV missing name/lot_size columns: {}", lines[0]);
                return result;
            }
            for (int i = 1; i < lines.length; i++) {
                String[] cols = lines[i].split(",");
                if (cols.length <= Math.max(nameIdx, lotIdx)) continue;
                String name = cols[nameIdx].trim();
                if (name.isEmpty() || result.containsKey(name)) continue;
                try {
                    int lot = Integer.parseInt(cols[lotIdx].trim());
                    if (lot > 0) result.put(name, lot);
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            log.error("Failed to fetch Zerodha instruments for lot sizes: {}", e.getMessage());
        }
        return result;
    }

    private double fetchLtp(String accessToken, String exchange, String symbol) {
        try {
            String instrument = exchange + ":" + symbol;
            // RestClient's .uri(String) overload treats the string as a URI TEMPLATE and
            // re-encodes it -- manually URL-encoding the ":" here first (as the old code did)
            // got double-encoded into %253A, which Kite doesn't match to any instrument and
            // silently returns empty data (no error, no exception -- just a wrong answer).
            // Building a raw java.net.URI bypasses template processing entirely.
            java.net.URI uri = java.net.URI.create(
                KITE_API_BASE + "/quote/ltp?i=" + java.net.URLEncoder.encode(instrument, StandardCharsets.UTF_8));
            String body = http.get()
                    .uri(uri)
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (!"success".equalsIgnoreCase(root.path("status").asText())) {
                log.warn("Zerodha LTP fetch for {} returned non-success: {}", instrument, body);
                return 0;
            }
            double ltp = root.path("data").path(instrument).path("last_price").asDouble(0);
            if (ltp <= 0) log.warn("Zerodha LTP fetch for {} returned no price: {}", instrument, body);
            return ltp;
        } catch (Exception e) {
            log.warn("Zerodha LTP fetch failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Cancelling Zerodha order: {}", orderId);
        try {
            http.delete()
                    .uri(KITE_API_BASE + "/orders/regular/" + orderId)
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Cancel order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Fetching Zerodha positions");
        try {
            String body = http.get()
                    .uri(KITE_API_BASE + "/portfolio/positions")
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);

            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (!"success".equalsIgnoreCase(root.path("status").asText())) return Collections.emptyList();

            List<BrokerPosition> result = new java.util.ArrayList<>();
            JsonNode net = root.path("data").path("net");
            if (net.isArray()) {
                for (JsonNode p : net) {
                    int qty = p.path("quantity").asInt(0);
                    if (qty == 0) continue;
                    BigDecimal avgPrice = new BigDecimal(p.path("average_price").asText("0"));
                    BigDecimal lastPrice = new BigDecimal(p.path("last_price").asText("0"));
                    BigDecimal unrealizedPnl = new BigDecimal(p.path("unrealised").asText("0"));
                    BigDecimal realizedPnl = new BigDecimal(p.path("realised").asText("0"));
                    result.add(new BrokerPosition(
                            p.path("tradingsymbol").asText(),
                            p.path("exchange").asText("NSE"),
                            qty, avgPrice, lastPrice, unrealizedPnl, realizedPnl,
                            p.path("product").asText("MIS")
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("getPositions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        try {
            String body = http.get()
                    .uri(KITE_API_BASE + "/user/margins")
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);

            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String balance = root.path("data").path("equity").path("available").path("live_balance").asText("0");
            return new BigDecimal(balance);
        } catch (Exception e) {
            log.warn("getAvailableMargin failed: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        try {
            String body = http.get()
                    .uri(KITE_API_BASE + "/orders/" + orderId)
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);

            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                return data.get(data.size() - 1).path("status").asText("UNKNOWN");
            }
        } catch (Exception e) {
            log.warn("getOrderStatus {} failed: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }

    public Map<String, Object> getOrderDetails(String accessToken, String orderId) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            String body = http.get()
                    .uri(KITE_API_BASE + "/orders/" + orderId)
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);

            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode order = data.get(data.size() - 1);
                details.put("status", order.path("status").asText("UNKNOWN"));
                details.put("average_price", order.path("average_price").asDouble(0.0));
                details.put("quantity", order.path("quantity").asInt(0));
                details.put("filled_quantity", order.path("filled_quantity").asInt(0));
                details.put("pending_quantity", order.path("pending_quantity").asInt(0));
                details.put("tradingsymbol", order.path("tradingsymbol").asText());
            }
        } catch (Exception e) {
            log.warn("getOrderDetails {} failed: {}", orderId, e.getMessage());
            details.put("status", "UNKNOWN");
        }
        return details;
    }
}
