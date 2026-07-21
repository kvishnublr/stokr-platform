package com.stokr.arbitrage;

import com.stokr.broker.BrokerOrderRequest;
import com.stokr.broker.BrokerOrderResponse;
import com.stokr.broker.ZerodhaAdapter;
import com.stokr.external.ZerodhaTokenManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionArbExecutionService {

    private final ZerodhaAdapter zerodhaAdapter;
    private final ZerodhaTokenManager tokenManager;

    private static final double MIN_MARGIN_BUFFER = 1.15;

    private static final Map<String, Double> HEDGED_MARGIN = Map.of(
        "NIFTY", 150000.0,
        "BANKNIFTY", 250000.0,
        "MIDCPNIFTY", 180000.0,
        "FINNIFTY", 200000.0
    );

    @Data
    public static class LegResult {
        private String symbol;
        private String side;
        private String orderId;
        private String status;
        private String message;
        private double requestedPrice;
        private double fillPrice;
        private int quantity;
        private int filledQuantity;
    }

    @Data
    public static class ExecutionResult {
        private boolean success;
        private boolean partialFill;
        private String action;
        private String underlying;
        private int strike;
        private List<LegResult> legs = new ArrayList<>();
        private String error;
        private BigDecimal marginAvailable;
        private BigDecimal marginRequired;
    }

    public ExecutionResult execute(String underlying, int strike, String action,
                                    double cePrice, double pePrice, double futPrice,
                                    double spotPrice, int lotSize) {
        ExecutionResult result = new ExecutionResult();
        result.action = action;
        result.underlying = underlying;
        result.strike = strike;

        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            result.success = false;
            result.error = "Market closed. Orders can only be placed between 9:15 AM and 3:30 PM IST.";
            return result;
        }

        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            result.success = false;
            result.error = "No valid Zerodha session. Please login via Brokers page.";
            return result;
        }

        BigDecimal availableMargin = zerodhaAdapter.getAvailableMargin(auth.getAccessToken());
        result.marginAvailable = availableMargin;
        double hedgedMargin = HEDGED_MARGIN.getOrDefault(underlying, 200000.0) * MIN_MARGIN_BUFFER;
        result.marginRequired = BigDecimal.valueOf(hedgedMargin);
        if (availableMargin.doubleValue() < hedgedMargin) {
            result.success = false;
            result.error = String.format("Insufficient margin. Available: ₹%,.0f, Required: ₹%,.0f (hedged + 15%% buffer)",
                availableMargin.doubleValue(), hedgedMargin);
            log.warn("Margin check failed: available={} required={}", availableMargin, hedgedMargin);
            return result;
        }

        LocalDate expiry = getWeeklyExpiryDate(underlying);
        String ceSymbol = buildNfoSymbol(underlying, expiry, strike, "CE");
        String peSymbol = buildNfoSymbol(underlying, expiry, strike, "PE");
        String futSymbol = buildNfoFutSymbol(underlying, expiry);

        log.info("Executing {} {}: CE={}, PE={}, FUT={} lot={} spot={} fut={} margin=₹{},.0f",
            action, underlying + " " + strike, ceSymbol, peSymbol, futSymbol, lotSize, spotPrice, futPrice, availableMargin.doubleValue());

        List<BrokerOrderRequest> orders;

        if ("CONVERSION".equals(action)) {
            orders = List.of(
                new BrokerOrderRequest(ceSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, cePrice, null, "NRML"),
                new BrokerOrderRequest(peSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, pePrice, null, "NRML"),
                new BrokerOrderRequest(futSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, futPrice, null, "NRML")
            );
        } else if ("REVERSAL".equals(action)) {
            orders = List.of(
                new BrokerOrderRequest(ceSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, cePrice, null, "NRML"),
                new BrokerOrderRequest(peSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, pePrice, null, "NRML"),
                new BrokerOrderRequest(futSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, futPrice, null, "NRML")
            );
        } else {
            result.success = false;
            result.error = "Unknown action: " + action + ". Only CONVERSION and REVERSAL supported.";
            return result;
        }

        List<String> placedOrderIds = new ArrayList<>();
        List<LegResult> allLegs = new ArrayList<>();

        for (BrokerOrderRequest order : orders) {
            LegResult leg = new LegResult();
            leg.symbol = order.symbol();
            leg.side = order.side().name();
            leg.quantity = order.quantity();
            leg.requestedPrice = order.price() != null ? order.price() : 0;

            try {
                BrokerOrderResponse response = zerodhaAdapter.placeOrder(auth.getAccessToken(), order);
                leg.orderId = response.orderId();
                leg.status = response.status();
                leg.message = response.message();

                if (response.orderId() != null) {
                    placedOrderIds.add(response.orderId());
                }
            } catch (Exception e) {
                leg.status = "ERROR";
                leg.message = e.getMessage();
                log.error("Order failed: {} {} — {}", order.side(), order.symbol(), e.getMessage());
            }

            allLegs.add(leg);
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        for (LegResult leg : allLegs) {
            if (leg.orderId != null) {
                try {
                    Map<String, Object> details = zerodhaAdapter.getOrderDetails(auth.getAccessToken(), leg.orderId);
                    String realStatus = (String) details.getOrDefault("status", "UNKNOWN");
                    double avgPrice = (double) details.getOrDefault("average_price", 0.0);
                    int filled = (int) details.getOrDefault("filled_quantity", 0);

                    leg.status = realStatus;
                    leg.fillPrice = avgPrice;
                    leg.filledQuantity = filled;
                    log.info("Order {} {}: status={} fillPrice={} filledQty={}", leg.side, leg.symbol, realStatus, avgPrice, filled);
                } catch (Exception e) {
                    log.warn("Could not verify fill for {}: {}", leg.orderId, e.getMessage());
                }
            }
        }

        List<LegResult> filledLegs = allLegs.stream()
            .filter(l -> "COMPLETE".equalsIgnoreCase(l.status))
            .toList();
        List<LegResult> unfilledLegs = allLegs.stream()
            .filter(l -> l.orderId != null && !"COMPLETE".equalsIgnoreCase(l.status))
            .toList();
        List<LegResult> errorLegs = allLegs.stream()
            .filter(l -> l.orderId == null || "ERROR".equals(l.status) || "REJECTED".equals(l.status))
            .toList();

        result.legs = allLegs;

        if (filledLegs.size() == 3) {
            result.success = true;
            result.partialFill = false;
            log.info("All 3 legs filled for {} {} {}", action, underlying, strike);
        } else if (filledLegs.isEmpty()) {
            result.success = false;
            result.partialFill = false;
            result.error = "No legs filled. " + allLegs.stream().map(l -> l.status).reduce((a, b) -> a + "," + b).orElse("unknown");
            log.warn("No legs filled for {} {} {}", action, underlying, strike);
        } else {
            result.partialFill = true;
            result.success = false;
            log.warn("PARTIAL FILL: {}/3 legs filled for {} {} {}. Square-off needed.",
                filledLegs.size(), action, underlying, strike);
            result.error = String.format("Partial fill: %d/%3 legs completed. Squaring off filled legs.", filledLegs.size());

            squareOffFilledLegs(auth.getAccessToken(), filledLegs, lotSize, action, underlying, strike);
        }

        return result;
    }

    private void squareOffFilledLegs(String token, List<LegResult> filledLegs, int lotSize,
                                       String action, String underlying, int strike) {
        log.warn("SQUARE-OFF: Closing {} filled legs for {} {} {} to prevent naked positions",
            filledLegs.size(), action, underlying, strike);

        for (LegResult leg : filledLegs) {
            if (leg.orderId == null || leg.fillPrice <= 0) continue;

            BrokerOrderRequest.Side closeSide = "BUY".equals(leg.side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
            double closePrice = leg.fillPrice;

            try {
                BrokerOrderRequest closeOrder = new BrokerOrderRequest(
                    leg.symbol, "NFO", closeSide, lotSize, closePrice, null, "NRML");
                BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeOrder);
                log.info("SQUARE-OFF order: {} {} {} @ {} — orderId={} status={}",
                    closeSide, leg.symbol, lotSize, closePrice, resp.orderId(), resp.status());
                if (!resp.isSuccess()) {
                    log.error("SQUARE-OFF FAILED for {}: {} — {}", leg.symbol, resp.status(), resp.message());
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    BrokerOrderResponse marketResp = zerodhaAdapter.placeOrder(token, new BrokerOrderRequest(
                        leg.symbol, "NFO", closeSide, lotSize, 0.0, null, "NRML"));
                    log.info("SQUARE-OFF MARKET retry: {} {} status={}", leg.symbol, closeSide, marketResp.status());
                }
            } catch (Exception e) {
                log.error("SQUARE-OFF exception for {}: {}", leg.symbol, e.getMessage());
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    private LocalDate getWeeklyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        if ("NIFTY".equals(underlying)) {
            LocalDate next = today;
            while (next.getDayOfWeek() != DayOfWeek.TUESDAY) {
                next = next.plusDays(1);
            }
            if (next.equals(today)) {
                java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
                if (nowIST.isAfter(java.time.LocalTime.of(15, 0))) {
                    next = next.plusWeeks(1);
                }
            }
            return next;
        }
        return getMonthlyExpiryDate();
    }

    private LocalDate getMonthlyExpiryDate() {
        LocalDate today = LocalDate.now();
        LocalDate lastTuesday = today.withDayOfMonth(today.lengthOfMonth());
        while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
            lastTuesday = lastTuesday.minusDays(1);
        }
        if (lastTuesday.isBefore(today)) {
            lastTuesday = lastTuesday.plusMonths(1).withDayOfMonth(lastTuesday.plusMonths(1).lengthOfMonth());
            while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
                lastTuesday = lastTuesday.minusDays(1);
            }
        }
        return lastTuesday;
    }

    private String buildNfoSymbol(String underlying, LocalDate expiry, int strike, String type) {
        String clean = underlying.replace(" ", "");
        int yy = expiry.getYear() % 100;
        boolean hasWeekly = "NIFTY".equals(clean);
        LocalDate monthly = getMonthlyExpiryDate();

        if (!hasWeekly || expiry.equals(monthly)) {
            String mon = expiry.getMonth().name().substring(0, 3);
            return String.format("%s%02d%s%d%s", clean, yy, mon, strike, type);
        } else {
            int month = expiry.getMonthValue();
            int day = expiry.getDayOfMonth();
            return String.format("%s%02d%d%02d%d%s", clean, yy, month, day, strike, type);
        }
    }

    private String buildNfoFutSymbol(String underlying, LocalDate expiry) {
        String clean = underlying.replace(" ", "");
        int yy = expiry.getYear() % 100;
        String mon = expiry.getMonth().name().substring(0, 3);
        return String.format("%s%02d%sFUT", clean, yy, mon);
    }
}
