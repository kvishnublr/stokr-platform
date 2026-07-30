package com.stokr.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class NaviaAdapter implements BrokerAdapter {

    @Value("${broker.navia.api-key:}")
    private String apiKey;

    @Value("${broker.navia.api-secret:}")
    private String apiSecret;

    private static final String NAVIA_API_BASE = "https://api.navia.co.in";

    private final RestClient http;
    private final BrokerAccountRepository repository;

    public NaviaAdapter(RestClient.Builder restClientBuilder, BrokerAccountRepository repository) {
        this.http = restClientBuilder.build();
        this.repository = repository;
    }

    @Override
    public String getBrokerName() {
        return "NAVIA";
    }

    @Override
    public String getAuthUrl() {
        throw new UnsupportedOperationException("Navia uses API key auth, not OAuth. Use /api/brokers/navia/apikey instead.");
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        // For Navia, the requestToken IS the API key; the "secret" is passed separately via connectApiKey
        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalStateException("Navia API key is required.");
        }
        log.info("Navia: using API key directly as access token");
        return new String[]{requestToken, ""};
    }

    /**
     * Connect Navia with API key + secret (no OAuth).
     * Creates/updates the BrokerAccount with the provided credentials.
     */
    public BrokerAccount connectApiKey(Long userId, String apiKey, String apiSecret) {
        BrokerAccount account = repository.findByUserIdAndBrokerNameAndStatus(userId, "NAVIA", "ACTIVE")
                .stream().findFirst().orElse(null);
        if (account == null) {
            account = repository.findByUserIdAndBrokerName(userId, "NAVIA")
                    .stream().findFirst().orElse(null);
            if (account != null) account.setStatus("ACTIVE");
        }
        if (account == null) {
            account = BrokerAccount.builder()
                    .userId(userId)
                    .brokerName("NAVIA")
                    .status("ACTIVE")
                    .build();
        }
        account.setAccessToken(apiKey);
        account.setRefreshToken(apiSecret);
        account.setTokenExpiry(java.time.Instant.now().plusSeconds(365 * 24 * 3600));
        account.setNaviaApiKey(apiKey);
        account.setNaviaApiSecret(apiSecret);
        return repository.save(account);
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Placing Navia order: {} {} {} qty={}",
                request.side(), request.symbol(), request.orderType(), request.quantity());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("tradingsymbol", request.symbol());
        form.add("exchange", request.exchange() != null ? request.exchange() : "NFO");
        form.add("transaction_type", request.side().name());
        form.add("product", request.productType() != null ? request.productType() : "NRML");
        form.add("quantity", String.valueOf(request.quantity()));
        form.add("validity", "DAY");

        if (request.price() != null && request.price() > 0) {
            form.add("order_type", "LIMIT");
            form.add("price", String.valueOf(request.price()));
        } else {
            form.add("order_type", "MARKET");
        }

        try {
            String body = http.post()
                    .uri(NAVIA_API_BASE + "/orders")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-API-Key", apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            String status = (String) root.getOrDefault("status", "");
            if ("success".equalsIgnoreCase(status) || Boolean.TRUE.equals(root.get("success"))) {
                String orderId = (String) root.get("order_id");
                if (orderId == null) orderId = (String) root.get("id");
                log.info("Navia order placed: {} {}", request.symbol(), orderId);
                return new BrokerOrderResponse(orderId, "OPEN", "Order placed");
            }
            String msg = (String) root.getOrDefault("message", "Order rejected");
            log.warn("Navia order rejected: {}", msg);
            return new BrokerOrderResponse(null, "REJECTED", msg);
        } catch (Exception e) {
            log.error("Navia placeOrder failed for {}: {}", request.symbol(), e.getMessage());
            return new BrokerOrderResponse(null, "REJECTED", e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Cancelling Navia order: {}", orderId);
        try {
            http.delete()
                    .uri(NAVIA_API_BASE + "/orders/" + orderId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Cancel Navia order {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Fetching Navia positions");
        try {
            String body = http.get()
                    .uri(NAVIA_API_BASE + "/portfolio/positions")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            List<Map<String, Object>> positions = (List<Map<String, Object>>) root.get("data");
            if (positions == null) return Collections.emptyList();

            List<BrokerPosition> result = new java.util.ArrayList<>();
            for (Map<String, Object> p : positions) {
                int qty = ((Number) p.getOrDefault("quantity", 0)).intValue();
                if (qty == 0) continue;
                BigDecimal avgPrice = new BigDecimal(p.getOrDefault("avg_price", "0").toString());
                BigDecimal lastPrice = new BigDecimal(p.getOrDefault("last_price", "0").toString());
                BigDecimal unrealizedPnl = new BigDecimal(p.getOrDefault("unrealized_pnl", "0").toString());
                BigDecimal realizedPnl = new BigDecimal(p.getOrDefault("realized_pnl", "0").toString());
                result.add(new BrokerPosition(
                        (String) p.getOrDefault("tradingsymbol", ""),
                        (String) p.getOrDefault("exchange", "NFO"),
                        qty, avgPrice, lastPrice, unrealizedPnl, realizedPnl,
                        (String) p.getOrDefault("product", "NRML")
                ));
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
                    .uri(NAVIA_API_BASE + "/user/margin")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            Object margin = root.get("available_margin");
            if (margin != null) {
                return new BigDecimal(margin.toString());
            }
        } catch (Exception e) {
            log.warn("getAvailableMargin failed: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        try {
            String body = http.get()
                    .uri(NAVIA_API_BASE + "/orders/" + orderId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            return (String) root.getOrDefault("status", "UNKNOWN");
        } catch (Exception e) {
            log.warn("getOrderStatus {} failed: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }
}