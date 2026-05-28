package com.stokr.broker.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.kite.ZerodhaKitePositionsParser;
import com.stokr.broker.model.BrokerCredentials;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.model.BrokerTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaAdapter implements BrokerAdapter {

    private static final String KITE_BASE = "https://api.kite.trade";

    private final ObjectMapper objectMapper;
    private final OutboundIpRestClientFactory ipClientFactory;

    @Override
    public String vendorCode() {
        return "ZERODHA";
    }

    private static final ThreadLocal<BrokerCredentials> ACTIVE_CREDENTIALS = new ThreadLocal<>();

    @Override
    public void authenticate(BrokerCredentials credentials) {
        if (credentials != null) {
            ACTIVE_CREDENTIALS.set(credentials);
        } else {
            ACTIVE_CREDENTIALS.remove();
        }
    }

    @Override
    public BrokerOrderResponse placeOrder(BrokerOrderRequest request) {
        String apiKey = request.apiKey();
        String accessToken = request.accessToken();
        if (apiKey == null || apiKey.isBlank() || accessToken == null || accessToken.isBlank()) {
            log.error("zerodha.place_order.missing_credentials symbol={}", request.symbol());
            throw new IllegalStateException(
                    "Zerodha credentials not available — broker session may have expired. Re-connect Zerodha.");
        }

        String[] parsed = parseSymbolExchange(request.symbol(), request.exchange());
        String exchange = parsed[0];
        String tradingsymbol = parsed[1];
        String side = request.side() != null ? request.side().trim().toUpperCase() : "BUY";
        String orderType = request.orderType() != null ? request.orderType().trim().toUpperCase() : "MARKET";
        String product = request.product() != null ? request.product().trim().toUpperCase() : "MIS";
        int qty = request.quantity() != null ? request.quantity().intValue() : 1;
        if (qty < 1) qty = 1;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("exchange", exchange);
        fields.put("tradingsymbol", tradingsymbol);
        fields.put("transaction_type", side);
        fields.put("quantity", String.valueOf(qty));
        fields.put("order_type", orderType);
        fields.put("product", product);
        fields.put("validity", "DAY");
        // Kite requires market_protection for MARKET and SL-M orders
        if ("MARKET".equalsIgnoreCase(orderType) || "SL-M".equalsIgnoreCase(orderType)) {
            fields.put("market_protection", "-1");
        }
        if ("LIMIT".equalsIgnoreCase(orderType) && request.limitPrice() != null) {
            fields.put("price", request.limitPrice().toPlainString());
        }
        // Tag helps correlate Kite orders back to platform orders (max 20 chars per Kite docs)
        if (request.clientOrderId() != null && !request.clientOrderId().isBlank()) {
            String tag = request.clientOrderId();
            if (tag.length() > 20) tag = tag.substring(tag.length() - 20);
            fields.put("tag", tag);
        }

        String formBody = encodeForm(fields);
        log.info("zerodha.place_order exchange={} symbol={} side={} qty={} orderType={} product={}",
                exchange, tradingsymbol, side, qty, orderType, product);

        try {
            RestClient http = ipClientFactory.clientFor(request.outboundIp());
            String raw = http.post()
                    .uri(KITE_BASE + "/orders/regular")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody)
                    .retrieve()
                    .body(String.class);

            JsonNode resp = readJson(raw);
            String status = resp.path("status").asText("");
            String kiteOrderId = resp.path("data").path("order_id").asText("");

            if (!"success".equalsIgnoreCase(status) || kiteOrderId.isBlank()) {
                String message = resp.path("message").asText("Kite order rejected");
                String errorType = resp.path("error_type").asText("");
                String detail = errorType.isBlank() ? message : errorType + ": " + message;
                log.error("zerodha.place_order.rejected symbol={} status={} detail={}", tradingsymbol, status, detail);
                throw new RuntimeException("Kite rejected order for " + tradingsymbol + " — " + detail);
            }

            log.info("zerodha.place_order.success kiteOrderId={} symbol={} side={} qty={}",
                    kiteOrderId, tradingsymbol, side, qty);
            // Kite order IDs are numeric strings; derive a deterministic UUID for platform storage
            UUID brokerOrderId = UUID.nameUUIDFromBytes(kiteOrderId.getBytes(StandardCharsets.UTF_8));
            return new BrokerOrderResponse(brokerOrderId, "ACCEPTED", request.symbol(), request.quantity(), kiteOrderId);

        } catch (RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            String detail = extractKiteError(body);
            log.error("zerodha.place_order.http_error symbol={} status={} detail={}",
                    tradingsymbol, ex.getStatusCode().value(), detail);
            throw new RuntimeException("Kite HTTP " + ex.getStatusCode().value() + " for " + tradingsymbol + " — " + detail, ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("zerodha.place_order.error symbol={} {}", tradingsymbol, ex.getMessage(), ex);
            throw new RuntimeException("Zerodha order placement failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public BrokerOrderResponse modifyOrder(UUID brokerOrderId, BrokerOrderRequest request) {
        return new BrokerOrderResponse(brokerOrderId, "MODIFIED", request.symbol(), request.quantity());
    }

    @Override
    public void cancelOrder(UUID brokerOrderId) {
        // no-op placeholder
    }

    @Override
    public List<BrokerPosition> getPositions() {
        BrokerCredentials credentials = ACTIVE_CREDENTIALS.get();
        if (credentials == null
                || credentials.apiKey() == null || credentials.apiKey().isBlank()
                || credentials.additionalToken() == null || credentials.additionalToken().isBlank()) {
            return Collections.emptyList();
        }
        try {
            RestClient http = ipClientFactory.clientFor(null);
            String raw = http.get()
                    .uri(KITE_BASE + "/portfolio/positions")
                    .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.additionalToken())
                    .retrieve()
                    .body(String.class);
            return ZerodhaKitePositionsParser.parse(readJson(raw));
        } catch (Exception ex) {
            log.warn("zerodha.get_positions.failed {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<BrokerOrderResponse> getOrders() {
        return Collections.emptyList();
    }

    @Override
    public void streamTicks(List<String> symbols, Consumer<BrokerTick> onTick) {
        // no-op placeholder
    }

    /** Parses "NSE:ITC" or "ITC" into [exchange, tradingsymbol]. Defaults to NSE. */
    private static String[] parseSymbolExchange(String symbol, String exchangeHint) {
        if (symbol != null && symbol.contains(":")) {
            String[] parts = symbol.split(":", 2);
            return new String[]{parts[0].trim().toUpperCase(), parts[1].trim().toUpperCase()};
        }
        String exchange = (exchangeHint != null && !exchangeHint.isBlank())
                ? exchangeHint.trim().toUpperCase()
                : "NSE";
        return new String[]{exchange, symbol != null ? symbol.trim().toUpperCase() : ""};
    }

    private static String encodeForm(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
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
            log.warn("zerodha.json_parse_failed {}", e.getClass().getSimpleName());
            return objectMapper.createObjectNode();
        }
    }

    private String extractKiteError(String body) {
        if (body == null || body.isBlank()) return "no response body";
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("message").asText("");
            String errorType = root.path("error_type").asText("");
            if (!message.isBlank() && !errorType.isBlank()) return errorType + ": " + message;
            if (!message.isBlank()) return message;
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
