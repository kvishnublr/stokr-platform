package com.stokr.user.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.common.events.auth.AuthAuditEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ZerodhaBrokerOperationsService {

    private final BrokerAccountRepository brokerAccountRepository;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final FieldCipher fieldCipher;
    private final ZerodhaKiteApiClient kiteApiClient;
    private final ZerodhaBrokerHealthService brokerHealthService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private static final Pattern TEST_SYMBOL = Pattern.compile("^[A-Z0-9]{1,25}$");
    private static final Set<String> TEST_EXCHANGES = Set.of("NSE", "BSE");
    private static final Set<String> TEST_PRODUCTS = Set.of("CNC", "MIS");
    private static final Set<String> TEST_VARIETIES = Set.of("REGULAR", "AMO");

    public record BrokerStatusDto(
            boolean connected,
            String brokerName,
            boolean tokenValid,
            Instant lastSyncAt,
            String accountId,
            String profileUserName,
            String profileEmail,
            String marginSummary,
            String health,
            boolean testOrderEnabled,
            boolean testOrderDryRun,
            String testTradeDisabledReason
    ) {
    }

    public record BrokerTestConnectionDto(boolean ok, String message, String profileUserName, String marginSummary) {
    }

    public record BrokerTestOrderRequest(
            String variety,
            String exchange,
            String tradingsymbol,
            String side,
            int quantity,
            String orderType,
            String product
    ) {
    }

    public record BrokerTestOrderDto(boolean dryRun, String orderId, String status, String message, String rawStatus) {
    }

    /** Sidebar / shell: cash-like balance from last persisted margin snapshot (Kite equity.available.cash). */
    public record BrokerAccountFundsDto(Double cashAvailable, Double availableMargin) {
    }

    @Transactional(readOnly = true)
    public List<BrokerAccountFundsDto> accountsFunds(UUID userId) {
        Optional<BrokerAccount> opt = brokerAccountRepository.findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId);
        if (opt.isEmpty()) {
            return List.of();
        }
        Double cash = extractEquityAvailableCash(opt.get().getMarginSnapshotJson());
        if (cash == null) {
            return List.of();
        }
        return List.of(new BrokerAccountFundsDto(cash, cash));
    }

    @Transactional(readOnly = true)
    public BrokerStatusDto status(UUID userId) {
        Optional<BrokerAccount> opt = brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA");
        if (opt.isEmpty()) {
            return new BrokerStatusDto(
                    false,
                    "Zerodha",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "UNKNOWN",
                    zerodhaBrokerProperties.isTestOrderEnabled(),
                    zerodhaBrokerProperties.isTestOrderDryRun(),
                    "Connect Zerodha first"
            );
        }
        BrokerAccount a = opt.get();
        boolean hasToken = a.getAccessTokenEnc() != null && !a.getAccessTokenEnc().isBlank();
        boolean connected = "CONNECTED".equalsIgnoreCase(Optional.ofNullable(a.getStatus()).orElse("")) && hasToken;
        boolean tokenValid = brokerHealthService.tokenLooksValid(a);
        String testTradeDisabledReason = deriveTestTradeDisabledReason(connected, tokenValid);
        String marginSummary = summarizeMarginsJson(a.getMarginSnapshotJson());
        String profileName = null;
        String profileEmail = null;
        try {
            if (a.getMetadataJson() != null && !a.getMetadataJson().isBlank()) {
                JsonNode meta = objectMapper.readTree(a.getMetadataJson());
                profileName = textOrNull(meta.path("kiteProfileUserName"));
                profileEmail = textOrNull(meta.path("kiteProfileEmail"));
            }
        } catch (Exception ignored) {
            // ignore
        }
        String health = brokerHealthService.deriveHealth(a, tokenValid);
        return new BrokerStatusDto(
                connected,
                "Zerodha",
                tokenValid,
                a.getLastSyncAt(),
                a.getBrokerUserId(),
                profileName,
                profileEmail,
                marginSummary,
                health,
                zerodhaBrokerProperties.isTestOrderEnabled(),
                zerodhaBrokerProperties.isTestOrderDryRun(),
                testTradeDisabledReason
        );
    }

    @Transactional
    public void disconnect(UUID userId) {
        Optional<BrokerAccount> opt = brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA");
        if (opt.isEmpty()) {
            return;
        }
        BrokerAccount a = opt.get();
        boolean hadToken = a.getAccessTokenEnc() != null && !a.getAccessTokenEnc().isBlank();
        boolean alreadyOff = "DISCONNECTED".equalsIgnoreCase(Optional.ofNullable(a.getStatus()).orElse("")) && !hadToken;
        if (alreadyOff) {
            return;
        }
        a.setAccessTokenEnc(null);
        a.setRefreshTokenEnc(null);
        a.setTokenExpiresAt(null);
        a.setStatus("DISCONNECTED");
        a.setHealthStatus("UNKNOWN");
        a.setMarginSnapshotJson(null);
        brokerAccountRepository.save(a);
        eventPublisher.publishEvent(new AuthAuditEvents.BrokerDisconnected(userId, a.getId(), Instant.now()));
    }

    @Transactional
    public BrokerTestConnectionDto testConnection(UUID userId) {
        Session s = requireSession(userId);
        boolean ok = false;
        String message = "failed";
        String profileUserName = null;
        String marginSummary = null;
        try {
            log.debug("broker.test_connection.profile_fetch userId={}", userId);
            JsonNode profile = kiteApiClient.getProfile(s.apiKey(), s.accessToken());
            if (!"success".equalsIgnoreCase(profile.path("status").asText())) {
                throw new BadRequestException("Kite profile call rejected: " + profile.path("message").asText("unknown error"));
            }
            JsonNode pdata = profile.path("data");
            profileUserName = textOrNull(pdata.path("user_name"));
            String profileEmail = textOrNull(pdata.path("email"));

            log.debug("broker.test_connection.margins_fetch userId={}", userId);
            JsonNode margins = kiteApiClient.getMargins(s.apiKey(), s.accessToken());
            if (!"success".equalsIgnoreCase(margins.path("status").asText())) {
                throw new BadRequestException("Kite margins call rejected: " + margins.path("message").asText("unknown error"));
            }
            marginSummary = buildMarginSummary(margins.path("data"));

            BrokerAccount a = s.account();
            ObjectNode meta;
            try {
                meta = a.getMetadataJson() != null && !a.getMetadataJson().isBlank()
                        ? (ObjectNode) objectMapper.readTree(a.getMetadataJson())
                        : objectMapper.createObjectNode();
            } catch (Exception e) {
                log.warn("broker.test_connection.metadata_parse_error: {}", e.getClass().getSimpleName());
                meta = objectMapper.createObjectNode();
            }
            meta.put("kiteProfileUserName", profileUserName == null ? "" : profileUserName);
            meta.put("kiteProfileEmail", profileEmail == null ? "" : profileEmail);
            a.setMetadataJson(meta.toString());
            a.setMarginSnapshotJson(margins.path("data").toString());
            a.setLastSyncAt(Instant.now());
            a.setHealthStatus("HEALTHY");
            brokerAccountRepository.save(a);
            ok = true;
            message = "ok";
            log.info("broker.test_connection.success userId={} profileUserName={}", userId, profileUserName);
        } catch (RestClientException ex) {
            log.warn("broker.test_connection.http_error class={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
            message = "http_error: " + ex.getMessage();
        } catch (BadRequestException ex) {
            message = ex.getMessage();
            log.warn("broker.test_connection.validation_error message={}", message);
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestConnection(userId, s.account().getId(), false, Instant.now()));
            throw ex;
        } catch (Exception ex) {
            log.error("broker.test_connection.error class={} message={}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
            message = "error: " + ex.getMessage();
        }
        eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestConnection(userId, s.account().getId(), ok, Instant.now()));
        return new BrokerTestConnectionDto(ok, message, profileUserName, marginSummary);
    }

    @Transactional
    public BrokerTestOrderDto placeTestOrder(UUID userId, BrokerTestOrderRequest req) {
        if (!zerodhaBrokerProperties.isTestOrderEnabled()) {
            throw new BadRequestException("Test orders are disabled (set STOKR_ZERODHA_TEST_ORDER_ENABLED=true to enable)");
        }
        Session s = requireSession(userId);
        String variety = req != null && req.variety() != null && !req.variety().isBlank() ? req.variety().trim().toUpperCase() : "REGULAR";
        if (!TEST_VARIETIES.contains(variety)) {
            throw new BadRequestException("variety must be REGULAR or AMO for test orders");
        }
        String exchange = req != null && req.exchange() != null && !req.exchange().isBlank() ? req.exchange().trim().toUpperCase() : "NSE";
        if (!TEST_EXCHANGES.contains(exchange)) {
            throw new BadRequestException("exchange must be NSE or BSE for test orders");
        }
        String symbol = req != null && req.tradingsymbol() != null && !req.tradingsymbol().isBlank()
                ? req.tradingsymbol().trim().toUpperCase()
                : "INFY";
        validateTestTradingsymbol(symbol);
        String side = req != null && req.side() != null && !req.side().isBlank() ? req.side().trim().toUpperCase() : "BUY";
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new BadRequestException("side must be BUY or SELL");
        }
        int qty = req != null ? req.quantity() : 1;
        if (qty < 1 || qty > 5) {
            throw new BadRequestException("quantity must be between 1 and 5 for test orders");
        }
        // Test path always uses MARKET — ignore client order_type to avoid accidental LIMIT/SL etc.
        String orderType = "MARKET";
        if (req != null && req.orderType() != null && !req.orderType().isBlank() && !"MARKET".equalsIgnoreCase(req.orderType().trim())) {
            throw new BadRequestException("test orders only support MARKET");
        }
        String product = req != null && req.product() != null && !req.product().isBlank()
                ? req.product().trim().toUpperCase()
                : "CNC";
        if (!TEST_PRODUCTS.contains(product)) {
            throw new BadRequestException("product must be CNC or MIS for test orders");
        }

        if (zerodhaBrokerProperties.isTestOrderDryRun()) {
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), "DRY_RUN", true, Instant.now()));
            return new BrokerTestOrderDto(true, "DRY_RUN", "simulated", "Dry run — no order sent to exchange", "success");
        }

        try {
            JsonNode resp = kiteApiClient.placeRegularOrder(
                    s.apiKey(),
                    s.accessToken(),
                    variety,
                    exchange,
                    symbol,
                    side,
                    qty,
                    orderType,
                    product
            );
            String st = resp.path("status").asText("");
            String orderId = resp.path("data").path("order_id").asText("");
            if (!"success".equalsIgnoreCase(st) || orderId.isBlank()) {
                String err = resp.path("message").asText("order_rejected");
                eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), "", false, Instant.now()));
                return new BrokerTestOrderDto(false, "", st, err, st);
            }
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), orderId, false, Instant.now()));
            return new BrokerTestOrderDto(false, orderId, st, "placed", st);
        } catch (RestClientResponseException ex) {
            String kiteDetail = extractKiteErrorDetail(ex);
            log.warn(
                    "broker.test_order.kite_rejected userId={} status={} detail={}",
                    userId,
                    ex.getStatusCode().value(),
                    kiteDetail
            );
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), "", false, Instant.now()));
            throw new BadRequestException("Kite rejected order: " + kiteDetail);
        } catch (RestClientException ex) {
            log.warn("broker.test_order.http class={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), "", false, Instant.now()));
            throw new BadRequestException("Kite order request failed before response from broker");
        }
    }

    private static void validateTestTradingsymbol(String symbol) {
        if (!TEST_SYMBOL.matcher(symbol).matches()) {
            throw new BadRequestException("tradingsymbol must be 1–25 uppercase letters or digits");
        }
    }

    private Session requireSession(UUID userId) {
        if (!zerodhaBrokerProperties.isConfigured()) {
            throw new BadRequestException("Zerodha is not configured");
        }
        BrokerAccount a = brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                .orElseThrow(() -> new BadRequestException("Connect Zerodha first"));
        if (a.getAccessTokenEnc() == null || a.getAccessTokenEnc().isBlank()) {
            throw new BadRequestException("Missing broker session");
        }
        String accessToken = fieldCipher.decrypt(a.getAccessTokenEnc());
        return new Session(zerodhaBrokerProperties.getApiKey(), accessToken, a);
    }

    private record Session(String apiKey, String accessToken, BrokerAccount account) {
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String t = n.asText();
        return t != null && !t.isBlank() ? t : null;
    }

    private Double extractEquityAvailableCash(String marginSnapshotJson) {
        if (marginSnapshotJson == null || marginSnapshotJson.isBlank()) {
            return null;
        }
        try {
            JsonNode data = objectMapper.readTree(marginSnapshotJson);
            JsonNode cashNode = data.path("equity").path("available").path("cash");
            if (cashNode == null || cashNode.isMissingNode() || cashNode.isNull()) {
                return null;
            }
            if (cashNode.isNumber()) {
                return cashNode.doubleValue();
            }
            String t = cashNode.asText();
            if (t == null || t.isBlank()) {
                return null;
            }
            return Double.parseDouble(t);
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeMarginsJson(String marginSnapshotJson) {
        if (marginSnapshotJson == null || marginSnapshotJson.isBlank()) {
            return null;
        }
        try {
            JsonNode data = objectMapper.readTree(marginSnapshotJson);
            JsonNode eq = data.path("equity");
            if (eq.isMissingNode() || eq.isNull()) {
                return data.toString().substring(0, Math.min(200, data.toString().length()));
            }
            String net = eq.path("net").asText("");
            String cash = eq.path("available").path("cash").asText("");
            return "equity.net=" + net + ", equity.available.cash=" + cash;
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildMarginSummary(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return null;
        }
        JsonNode eq = data.path("equity");
        String net = eq.path("net").asText("");
        String cash = eq.path("available").path("cash").asText("");
        return "equity.net=" + net + ", equity.available.cash=" + cash;
    }

    private String deriveTestTradeDisabledReason(boolean connected, boolean tokenValid) {
        if (!connected) {
            return "Connect Zerodha first";
        }
        if (!tokenValid) {
            return "Reconnect Zerodha to refresh session";
        }
        if (!zerodhaBrokerProperties.isTestOrderEnabled()) {
            return "Sample trade is disabled by server config";
        }
        return null;
    }

    private String extractKiteErrorDetail(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                String message = textOrNull(root.path("message"));
                String errorType = textOrNull(root.path("error_type"));
                if (message != null && errorType != null) {
                    return errorType + " - " + message;
                }
                if (message != null) {
                    return message;
                }
            } catch (Exception parseEx) {
                log.debug("broker.test_order.kite_error_parse_failed class={}", parseEx.getClass().getSimpleName());
            }
        }
        String statusText = ex.getStatusText();
        if (statusText != null && !statusText.isBlank()) {
            return statusText;
        }
        return "HTTP " + ex.getStatusCode().value();
    }
}
