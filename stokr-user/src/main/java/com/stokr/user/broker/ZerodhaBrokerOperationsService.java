package com.stokr.user.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stokr.broker.kite.ZerodhaKitePositionsParser;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.model.BrokerPositionDetail;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final int TEST_ORDER_POLL_MS = 400;
    private static final int TEST_ORDER_POLL_MAX_ATTEMPTS = 20;

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

    public record BrokerTestOrderLegDto(
            String orderId,
            String status,
            String message,
            Integer filledQuantity,
            Double averagePrice
    ) {
    }

    /**
     * Round-trip sample trade result: entry leg, optional auto square-off exit leg, and summary fields
     * ({@code orderId}/{@code status}/{@code message}) kept for backward compatibility.
     */
    public record BrokerTestOrderDto(
            boolean dryRun,
            String orderId,
            String status,
            String message,
            String rawStatus,
            BrokerTestOrderLegDto entry,
            BrokerTestOrderLegDto exit,
            String squareOffStatus,
            Double pnl
    ) {
    }

    public record BrokerOpenOrderDto(
            String orderId,
            String parentOrderId,
            String exchange,
            String symbol,
            String side,
            String product,
            String variety,
            String orderType,
            Integer quantity,
            Double price,
            String status,
            Instant orderTimestamp,
            String statusMessage,
            Integer filledQuantity,
            Double averagePrice
    ) {
    }

    public record BrokerCancelOrderDto(boolean ok, String orderId, String status, String message) {
    }

    /** Sidebar / shell: cash-like balance from last persisted margin snapshot (Kite equity.available.cash). */
    public record BrokerAccountFundsDto(Double cashAvailable, Double availableMargin) {
    }

    @Transactional
    public List<BrokerAccountFundsDto> accountsFunds(UUID userId) {
        Optional<BrokerAccount> opt = brokerAccountRepository.findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId);
        if (opt.isEmpty()) {
            return List.of();
        }
        BrokerAccount account = opt.get();
        Double cash = extractEquityAvailableCash(account.getMarginSnapshotJson());
        Double availableMargin = extractEquityAvailableMargin(account.getMarginSnapshotJson());
        if (cash == null && availableMargin == null) {
            // Backfill missing snapshot from live broker when session exists, so sidebar margin is not blank.
            try {
                Session s = requireSession(userId);
                JsonNode margins = kiteApiClient.getMargins(s.apiKey(), s.accessToken(), s.outboundIp());
                if ("success".equalsIgnoreCase(margins.path("status").asText())) {
                    JsonNode data = margins.path("data");
                    account.setMarginSnapshotJson(data.toString());
                    account.setLastSyncAt(Instant.now());
                    account.setHealthStatus("HEALTHY");
                    brokerAccountRepository.save(account);
                    cash = extractEquityAvailableCash(account.getMarginSnapshotJson());
                    availableMargin = extractEquityAvailableMargin(account.getMarginSnapshotJson());
                }
            } catch (Exception ex) {
                log.debug("broker.accounts_funds.live_fetch_skipped userId={} reason={}", userId, ex.getClass().getSimpleName());
            }
        }
        if (cash == null && availableMargin == null) {
            return List.of();
        }
        Double normalizedCash = cash != null ? cash : availableMargin;
        Double normalizedMargin = availableMargin != null ? availableMargin : cash;
        return List.of(new BrokerAccountFundsDto(normalizedCash, normalizedMargin));
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

    @Transactional(readOnly = true)
    public List<BrokerOpenOrderDto> openOrders(UUID userId) {
        return fetchOrders(userId, true, 500);
    }

    @Transactional(readOnly = true)
    public List<BrokerOpenOrderDto> recentOrders(UUID userId, int limit) {
        int capped = Math.max(1, Math.min(500, limit));
        return fetchOrders(userId, false, capped);
    }

    @Transactional(readOnly = true)
    public List<BrokerPosition> fetchBrokerPositions(UUID userId) {
        return fetchBrokerPositionDetails(userId).stream()
                .filter(d -> d.quantity() != null && d.quantity().compareTo(java.math.BigDecimal.ZERO) != 0)
                .map(d -> new BrokerPosition(d.symbolKey(), d.quantity(), d.averagePrice()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BrokerPositionDetail> fetchBrokerPositionDetails(UUID userId) {
        Session s = requireSession(userId);
        JsonNode payload = kiteApiClient.getPositions(s.apiKey(), s.accessToken(), s.outboundIp());
        if (payload != null && !"success".equalsIgnoreCase(payload.path("status").asText(""))) {
            log.warn("zerodha.positions.api_error user={} status={} message={}",
                    userId, payload.path("status").asText(""), payload.path("message").asText(""));
        }
        List<BrokerPositionDetail> positions = ZerodhaKitePositionsParser.parseDetails(payload);
        log.info("zerodha.positions.fetched user={} count={}", userId, positions.size());
        return positions;
    }

    private List<BrokerOpenOrderDto> fetchOrders(UUID userId, boolean onlyOpen, int limit) {
        Session s = requireSession(userId);
        JsonNode payload = kiteApiClient.getOrders(s.apiKey(), s.accessToken(), s.outboundIp());
        if (!"success".equalsIgnoreCase(payload.path("status").asText(""))) {
            return List.of();
        }
        JsonNode rows = payload.path("data");
        if (!rows.isArray()) {
            return List.of();
        }
        List<BrokerOpenOrderDto> out = new ArrayList<>();
        for (JsonNode row : rows) {
            String status = row.path("status").asText("");
            if (onlyOpen && isTerminalOrderStatus(status)) {
                continue;
            }
            out.add(new BrokerOpenOrderDto(
                    blankToNull(row.path("order_id").asText("")),
                    blankToNull(row.path("parent_order_id").asText("")),
                    blankToNull(row.path("exchange").asText("")),
                    blankToNull(row.path("tradingsymbol").asText("")),
                    blankToNull(row.path("transaction_type").asText("")),
                    blankToNull(row.path("product").asText("")),
                    blankToNull(row.path("variety").asText("")),
                    blankToNull(row.path("order_type").asText("")),
                    row.path("quantity").isNumber() ? row.path("quantity").asInt() : null,
                    row.path("price").isNumber() ? row.path("price").asDouble() : null,
                    status,
                    parseKiteTimestamp(row.path("order_timestamp").asText("")),
                    blankToNull(row.path("status_message").asText("")),
                    row.path("filled_quantity").isNumber() ? row.path("filled_quantity").asInt() : null,
                    row.path("average_price").isNumber() ? row.path("average_price").asDouble() : null
            ));
        }
        out.sort((a, b) -> {
            Instant at = a.orderTimestamp();
            Instant bt = b.orderTimestamp();
            if (at == null && bt == null) return 0;
            if (at == null) return 1;
            if (bt == null) return -1;
            return bt.compareTo(at);
        });
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    @Transactional
    public BrokerCancelOrderDto cancelOpenOrder(UUID userId, String orderId, String variety) {
        Session s = requireSession(userId);
        String safeOrderId = orderId == null ? "" : orderId.trim();
        if (safeOrderId.isBlank()) {
            throw new BadRequestException("orderId is required");
        }
        String safeVariety = variety == null || variety.isBlank() ? "regular" : variety.trim().toLowerCase();
        JsonNode payload = kiteApiClient.cancelOrder(s.apiKey(), s.accessToken(), safeVariety, safeOrderId, s.outboundIp());
        String status = payload.path("status").asText("");
        boolean ok = "success".equalsIgnoreCase(status);
        String message = ok
                ? "Cancelled"
                : payload.path("message").asText("cancel failed");
        return new BrokerCancelOrderDto(ok, safeOrderId, status, message);
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
            JsonNode profile = kiteApiClient.getProfile(s.apiKey(), s.accessToken(), s.outboundIp());
            if (!"success".equalsIgnoreCase(profile.path("status").asText())) {
                throw new BadRequestException("Kite profile call rejected: " + profile.path("message").asText("unknown error"));
            }
            JsonNode pdata = profile.path("data");
            profileUserName = textOrNull(pdata.path("user_name"));
            String profileEmail = textOrNull(pdata.path("email"));

            log.debug("broker.test_connection.margins_fetch userId={}", userId);
            JsonNode margins = kiteApiClient.getMargins(s.apiKey(), s.accessToken(), s.outboundIp());
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
                : "ITC";
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
                : "MIS";
        if (!TEST_PRODUCTS.contains(product)) {
            throw new BadRequestException("product must be CNC or MIS for test orders");
        }

        if (zerodhaBrokerProperties.isTestOrderDryRun()) {
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), "DRY_RUN", true, Instant.now()));
            BrokerTestOrderLegDto entryLeg = new BrokerTestOrderLegDto(
                    "DRY_RUN_ENTRY",
                    "simulated",
                    "Entry MARKET " + side + " x" + qty,
                    qty,
                    null
            );
            BrokerTestOrderLegDto exitLeg = new BrokerTestOrderLegDto(
                    "DRY_RUN_EXIT",
                    "simulated",
                    "Exit MARKET " + oppositeSide(side) + " x" + qty + " (auto square-off)",
                    qty,
                    null
            );
            return new BrokerTestOrderDto(
                    true,
                    "DRY_RUN_ENTRY",
                    "simulated",
                    "Dry run — round-trip simulated (no orders sent to exchange)",
                    "success",
                    entryLeg,
                    exitLeg,
                    "SIMULATED",
                    null
            );
        }

        try {
            return placeRoundTripTestOrder(userId, s, variety, exchange, symbol, side, qty, orderType, product);
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

    private BrokerTestOrderDto placeRoundTripTestOrder(
            UUID userId,
            Session s,
            String variety,
            String exchange,
            String symbol,
            String side,
            int qty,
            String orderType,
            String product
    ) {
        JsonNode entryResp = kiteApiClient.placeRegularOrder(
                s.apiKey(),
                s.accessToken(),
                variety,
                exchange,
                symbol,
                side,
                qty,
                orderType,
                product,
                s.outboundIp()
        );
        String entryApiStatus = entryResp.path("status").asText("");
        String entryOrderId = entryResp.path("data").path("order_id").asText("");
        if (!"success".equalsIgnoreCase(entryApiStatus) || entryOrderId.isBlank()) {
            String err = entryResp.path("message").asText("order_rejected");
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), "", false, Instant.now()));
            BrokerTestOrderLegDto entryLeg = new BrokerTestOrderLegDto("", entryApiStatus, err, 0, null);
            return new BrokerTestOrderDto(
                    false,
                    "",
                    entryApiStatus,
                    err,
                    entryApiStatus,
                    entryLeg,
                    null,
                    "ENTRY_REJECTED",
                    null
            );
        }

        KiteOrderSnapshot entrySnap = pollOrderSnapshot(s, entryOrderId);
        BrokerTestOrderLegDto entryLeg = toLeg(entryOrderId, entrySnap, "Entry placed");
        int exitQty = entrySnap.filledQuantity() > 0 ? entrySnap.filledQuantity() : qty;
        String squareOffStatus;
        BrokerTestOrderLegDto exitLeg = null;
        String summaryMessage;
        Double pnl = null;

        if (!isOrderComplete(entrySnap.status())) {
            squareOffStatus = "ENTRY_NOT_FILLED";
            summaryMessage = "Entry order " + entryOrderId + " not filled in time — square-off skipped";
            log.warn(
                    "broker.test_order.entry_not_filled userId={} orderId={} status={}",
                    userId,
                    entryOrderId,
                    entrySnap.status()
            );
        } else {
            String exitSide = oppositeSide(side);
            JsonNode exitResp = kiteApiClient.placeRegularOrder(
                    s.apiKey(),
                    s.accessToken(),
                    variety,
                    exchange,
                    symbol,
                    exitSide,
                    exitQty,
                    orderType,
                    product,
                    s.outboundIp()
            );
            String exitApiStatus = exitResp.path("status").asText("");
            String exitOrderId = exitResp.path("data").path("order_id").asText("");
            if (!"success".equalsIgnoreCase(exitApiStatus) || exitOrderId.isBlank()) {
                String err = exitResp.path("message").asText("exit_rejected");
                exitLeg = new BrokerTestOrderLegDto("", exitApiStatus, err, 0, null);
                squareOffStatus = "EXIT_REJECTED";
                summaryMessage = "Entry filled; exit rejected: " + err;
                log.warn("broker.test_order.exit_rejected userId={} entryOrderId={} detail={}", userId, entryOrderId, err);
            } else {
                KiteOrderSnapshot exitSnap = pollOrderSnapshot(s, exitOrderId);
                exitLeg = toLeg(exitOrderId, exitSnap, "Exit placed (auto square-off)");
                squareOffStatus = isOrderComplete(exitSnap.status()) ? "COMPLETED" : "EXIT_PENDING";
                summaryMessage = squareOffStatus.equals("COMPLETED")
                        ? "Round-trip complete — entry " + entryOrderId + ", exit " + exitOrderId
                        : "Entry filled; exit " + exitOrderId + " still " + exitSnap.status();
                pnl = estimateRoundTripPnl(side, entrySnap.averagePrice(), exitSnap.averagePrice(), exitQty);
            }
        }

        eventPublisher.publishEvent(new AuthAuditEvents.BrokerTestOrder(userId, s.account().getId(), entryOrderId, false, Instant.now()));
        return new BrokerTestOrderDto(
                false,
                entryOrderId,
                "success",
                summaryMessage,
                entryApiStatus,
                entryLeg,
                exitLeg,
                squareOffStatus,
                pnl
        );
    }

    private static String oppositeSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? "BUY" : "SELL";
    }

    private static boolean isOrderComplete(String status) {
        return status != null && "COMPLETE".equalsIgnoreCase(status.trim());
    }

    private BrokerTestOrderLegDto toLeg(String orderId, KiteOrderSnapshot snap, String fallbackMessage) {
        String message = snap.statusMessage() != null && !snap.statusMessage().isBlank()
                ? snap.statusMessage()
                : fallbackMessage;
        return new BrokerTestOrderLegDto(
                orderId,
                snap.status(),
                message,
                snap.filledQuantity() > 0 ? snap.filledQuantity() : null,
                snap.averagePrice()
        );
    }

    private static Double estimateRoundTripPnl(String entrySide, Double entryAvg, Double exitAvg, int qty) {
        if (entryAvg == null || exitAvg == null || qty <= 0) {
            return null;
        }
        double spread = "BUY".equalsIgnoreCase(entrySide) ? (exitAvg - entryAvg) : (entryAvg - exitAvg);
        return Math.round(spread * qty * 100.0) / 100.0;
    }

    private KiteOrderSnapshot pollOrderSnapshot(Session s, String orderId) {
        KiteOrderSnapshot last = KiteOrderSnapshot.empty(orderId);
        for (int attempt = 0; attempt < TEST_ORDER_POLL_MAX_ATTEMPTS; attempt++) {
            last = fetchOrderSnapshot(s, orderId).orElse(last);
            if (isTerminalOrderStatus(last.status())) {
                return last;
            }
            try {
                Thread.sleep(TEST_ORDER_POLL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private Optional<KiteOrderSnapshot> fetchOrderSnapshot(Session s, String orderId) {
        JsonNode payload = kiteApiClient.getOrders(s.apiKey(), s.accessToken(), s.outboundIp());
        if (!"success".equalsIgnoreCase(payload.path("status").asText(""))) {
            return Optional.empty();
        }
        JsonNode rows = payload.path("data");
        if (!rows.isArray()) {
            return Optional.empty();
        }
        for (JsonNode row : rows) {
            if (!orderId.equals(row.path("order_id").asText(""))) {
                continue;
            }
            int filled = row.path("filled_quantity").isNumber() ? row.path("filled_quantity").asInt() : 0;
            Double avg = row.path("average_price").isNumber() && row.path("average_price").asDouble() > 0
                    ? row.path("average_price").asDouble()
                    : null;
            return Optional.of(new KiteOrderSnapshot(
                    orderId,
                    row.path("status").asText(""),
                    blankToNull(row.path("status_message").asText("")),
                    filled,
                    avg
            ));
        }
        return Optional.empty();
    }

    private record KiteOrderSnapshot(
            String orderId,
            String status,
            String statusMessage,
            int filledQuantity,
            Double averagePrice
    ) {
        static KiteOrderSnapshot empty(String orderId) {
            return new KiteOrderSnapshot(orderId, "OPEN", null, 0, null);
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
        String accessToken = decodeStoredBrokerToken(a.getAccessTokenEnc());
        if (accessToken == null || accessToken.isBlank()) {
            throw new BadRequestException("Missing broker session");
        }
        return new Session(zerodhaBrokerProperties.getApiKey(), accessToken, a, a.getOutboundIp());
    }

    private String decodeStoredBrokerToken(String stored) {
        try {
            return fieldCipher.decrypt(stored);
        } catch (RuntimeException ex) {
            String trimmed = stored == null ? "" : stored.trim();
            int colon = trimmed.indexOf(':');
            return colon >= 0 && colon + 1 < trimmed.length()
                    ? trimmed.substring(colon + 1).trim()
                    : trimmed;
        }
    }

    private record Session(String apiKey, String accessToken, BrokerAccount account, String outboundIp) {
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
            return asDoubleOrNull(cashNode);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Kite may report zero for available.cash while usable headroom appears in live/opening balance fields.
     */
    private Double extractEquityAvailableMargin(String marginSnapshotJson) {
        if (marginSnapshotJson == null || marginSnapshotJson.isBlank()) {
            return null;
        }
        try {
            JsonNode data = objectMapper.readTree(marginSnapshotJson);
            JsonNode available = data.path("equity").path("available");
            Double liveBalance = asDoubleOrNull(available.path("live_balance"));
            Double openingBalance = asDoubleOrNull(available.path("opening_balance"));
            Double cash = asDoubleOrNull(available.path("cash"));
            if (liveBalance != null) {
                return liveBalance;
            }
            if (openingBalance != null) {
                return openingBalance;
            }
            return cash;
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
            String liveBalance = eq.path("available").path("live_balance").asText("");
            return "equity.net=" + net + ", equity.available.cash=" + cash + ", equity.available.live_balance=" + liveBalance;
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
        String liveBalance = eq.path("available").path("live_balance").asText("");
        return "equity.net=" + net + ", equity.available.cash=" + cash + ", equity.available.live_balance=" + liveBalance;
    }

    private static Double asDoubleOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        String t = node.asText();
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            return null;
        }
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

    private static boolean isTerminalOrderStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.trim().toUpperCase();
        return s.equals("COMPLETE")
                || s.equals("CANCELLED")
                || s.equals("REJECTED");
    }

    private static Instant parseKiteTimestamp(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(ts, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
