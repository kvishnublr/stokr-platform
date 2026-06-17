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
            return refreshToken != null ? new String[]{accessToken, refreshToken} : new String[]{accessToken};
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
        log.info("Placing Zerodha order: {} {} {} qty={}",
                request.side(), request.symbol(), request.orderType(), request.quantity());
        // TODO: POST /orders/regular via Kite Connect API
        throw new UnsupportedOperationException("Zerodha order placement requires kiteconnect SDK");
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Cancelling Zerodha order: {}", orderId);
        // TODO: DELETE /orders/regular/{order_id}
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Fetching Zerodha positions");
        // TODO: GET /portfolio/positions
        return Collections.emptyList();
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("Fetching Zerodha margins");
        // TODO: GET /user/margins
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        log.info("Fetching Zerodha order status: {}", orderId);
        // TODO: GET /orders/{order_id}
        return "UNKNOWN";
    }
}
