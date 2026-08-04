package com.stokr.arbitrage;

import com.stokr.broker.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionArbAutoExecService {

    private final OptionArbOpportunityRepository oppRepo;
    private final LivePositionRepository positionRepo;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;
    private final OptionChainService optionChainService;

    private final ConcurrentHashMap<String, Map<String, Object>> autoExecSettings = new ConcurrentHashMap<>();

    private final List<Map<String, Object>> execLogs = Collections.synchronizedList(new ArrayList<>());

    @PostConstruct
    public void init() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", false);
        defaults.put("broker", "NAVIA");
        defaults.put("niftyEnabled", false);
        defaults.put("niftyMinEdge", 2000.0);
        defaults.put("niftyLots", 1);
        defaults.put("bankniftyEnabled", false);
        defaults.put("bankniftyMinEdge", 2000.0);
        defaults.put("bankniftyLots", 1);
        defaults.put("finniftyEnabled", false);
        defaults.put("finniftyMinEdge", 2000.0);
        defaults.put("finniftyLots", 1);
        defaults.put("midcpniftyEnabled", false);
        defaults.put("midcpniftyMinEdge", 2000.0);
        defaults.put("midcpniftyLots", 1);
        defaults.put("maxOpenPositions", 5);
        defaults.put("maxDailyLoss", 5000.0);
        autoExecSettings.put("global", defaults);
    }

    public Map<String, Object> getSettings() {
        return new LinkedHashMap<>(autoExecSettings.getOrDefault("global", Map.of()));
    }

    public void updateSetting(String key, String value) {
        Map<String, Object> s = autoExecSettings.computeIfAbsent("global", k -> new LinkedHashMap<>());
        if ("enabled".equals(key)) s.put("enabled", Boolean.parseBoolean(value));
        else if (key.endsWith("Enabled")) s.put(key, Boolean.parseBoolean(value));
        else if (key.endsWith("MinEdge") || key.equals("maxDailyLoss")) s.put(key, Double.parseDouble(value));
        else if (key.endsWith("Lots") || key.equals("maxOpenPositions")) s.put(key, Integer.parseInt(value));
        else s.put(key, value);
    }

    public List<Map<String, Object>> getExecLogs() {
        List<Map<String, Object>> list = new ArrayList<>(execLogs);
        Collections.reverse(list);
        return list.stream().limit(100).toList();
    }

    /**
     * IMMEDIATE: called right after scan saves new opportunities.
     * Evaluates each new signal against thresholds and executes instantly.
     */
    public void evaluateAndExecute(List<OptionArbOpportunity> newOpps) {
        Map<String, Object> settings = getSettings();
        if (!Boolean.TRUE.equals(settings.get("enabled"))) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        int maxPositions = (int) settings.getOrDefault("maxOpenPositions", 5);
        long currentOpen = positionRepo.countAllOpen();
        if (currentOpen >= maxPositions) return;

        Long userId;
        BrokerAccount account;
        BrokerAdapter adapter;
        try {
            userId = brokerAccountRepo.findByStatus("ACTIVE").stream()
                    .findFirst().map(BrokerAccount::getUserId).orElse(null);
            if (userId == null) return;
            List<BrokerAccount> accounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, broker, "ACTIVE");
            if (accounts.isEmpty()) return;
            account = accounts.get(0);
            adapter = brokerService.getAdapter(broker);
        } catch (Exception e) {
            log.error("Auto-exec: broker setup failed: {}", e.getMessage());
            return;
        }

        double availableMargin = 0;
        try {
            BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
            availableMargin = margin != null ? margin.doubleValue() : 0;
        } catch (Exception e) {
            log.error("Auto-exec: margin check failed: {}", e.getMessage());
            addLog("MARGIN", "ERROR", "Failed to fetch margin: " + e.getMessage());
            return;
        }

        double maxDailyLoss = ((Number) settings.getOrDefault("maxDailyLoss", 5000.0)).doubleValue();
        double todayPnl = positionRepo.findAllOpen().stream()
                .filter(p -> p.getCurrentPnl() != null)
                .mapToDouble(p -> p.getCurrentPnl().doubleValue())
                .sum();
        if (todayPnl < -maxDailyLoss) {
            addLog("RISK", "STOPPED", "Daily loss limit hit: ₹" + String.format("%.0f", todayPnl));
            return;
        }

        String strategyFilter = (String) settings.getOrDefault("strategyFilter", "ALL");

        for (OptionArbOpportunity opp : newOpps) {
            if (currentOpen >= maxPositions) break;
            if (opp.getUnderlying() == null || opp.getEdgeAfterCosts() == null) continue;

            String key = opp.getUnderlying().toLowerCase();
            boolean enabled = Boolean.TRUE.equals(settings.get(key + "Enabled"));
            if (!enabled) continue;

            String stratType = opp.getStrategyType() != null ? opp.getStrategyType().toUpperCase() : "";
            String oppAction = opp.getAction() != null ? opp.getAction().toUpperCase() : "";
            if ("PARITY".equals(strategyFilter) && !stratType.contains("PARITY") && !stratType.contains("BID")) continue;
            if ("BOX".equals(strategyFilter) && !stratType.contains("BOX")) continue;

            double minEdge = ((Number) settings.getOrDefault(key + "MinEdge", 2000.0)).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() < minEdge) continue;
            if (opp.getExpiryDate() == null || opp.getStrike() == null) continue;

            if (positionRepo.findByUserIdAndStatusOrderByEnteredAtDesc(userId, "OPEN").stream()
                    .anyMatch(p -> opp.getId() != null && opp.getId().equals(p.getOpportunityId()))) continue;

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            double hedgedMargin = estimateHedgedMargin(opp.getUnderlying(), lots);
            if (hedgedMargin > availableMargin * 0.9) {
                addLog("MARGIN", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " needs ₹" + String.format("%.0f", hedgedMargin)
                        + " but only ₹" + String.format("%.0f", availableMargin) + " available");
                continue;
            }

            addLog("SIGNAL", "FIRING", opp.getUnderlying() + " " + opp.getStrike()
                    + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " > threshold ₹" + String.format("%.0f", minEdge) + " — executing NOW");
            boolean opened = executeTrade(account, adapter, opp, lots, userId);
            if (opened) {
                currentOpen++;
                availableMargin -= hedgedMargin;
            }
        }
    }

    private boolean executeTrade(BrokerAccount account, BrokerAdapter adapter, OptionArbOpportunity opp, int lots, Long userId) {
        int lotSize = getLotSize(opp.getUnderlying());
        String action = opp.getAction() != null ? opp.getAction().toUpperCase() : "";
        boolean isConversion = "CONVERSION".equals(action) || action.contains("BUY CE+PE") || action.contains("BUY CE");

        LivePosition position = LivePosition.builder()
                .userId(userId)
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(opp.getAction())
                .strategyType(opp.getStrategyType())
                .lots(lots)
                .lotSize(lotSize)
                .ceEntryPrice(opp.getCeEntryPrice())
                .peEntryPrice(opp.getPeEntryPrice())
                .futEntryPrice(opp.getFuturesPrice())
                .targetEdge(opp.getEdgeAfterCosts())
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
            String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
            String futSymbol = optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate());
            position.setCeSymbol(ceSymbol);
            position.setPeSymbol(peSymbol);
            position.setFutSymbol(futSymbol);

            int ceQty = lots * lotSize;
            int peQty = lots * lotSize;
            int futQty = lots * lotSize;

            double futPrice = opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0;

            // BUY-first: place BUY legs first, then hedge with sell legs.
            // CONVERSION: BUY CE, SELL FUT, SELL PE
            // REVERSAL: BUY PE, BUY FUT, SELL CE
            List<PlannedLeg> orderPlan;
            if (isConversion) {
                orderPlan = List.of(
                    new PlannedLeg(ceSymbol, BrokerOrderRequest.Side.BUY, ceQty, 0.0, "ce"),
                    new PlannedLeg(futSymbol, BrokerOrderRequest.Side.SELL, futQty, futPrice, "fut"),
                    new PlannedLeg(peSymbol, BrokerOrderRequest.Side.SELL, peQty, 0.0, "pe")
                );
            } else {
                orderPlan = List.of(
                    new PlannedLeg(peSymbol, BrokerOrderRequest.Side.BUY, peQty, 0.0, "pe"),
                    new PlannedLeg(futSymbol, BrokerOrderRequest.Side.BUY, futQty, futPrice, "fut"),
                    new PlannedLeg(ceSymbol, BrokerOrderRequest.Side.SELL, ceQty, 0.0, "ce")
                );
            }

            List<PlacedLeg> placedLegs = new ArrayList<>();

            for (PlannedLeg leg : orderPlan) {
                String symbol = leg.symbol();
                BrokerOrderRequest.Side side = leg.side();
                int qty = leg.quantity();
                double price = leg.price();
                String legKey = leg.legKey();

                BrokerOrderRequest req = BrokerOrderRequest.builder()
                        .symbol(symbol).exchange("NFO")
                        .side(side)
                        .quantity(qty).price(price)
                        .orderType(price > 0 ? BrokerOrderRequest.OrderType.LIMIT : BrokerOrderRequest.OrderType.MARKET)
                        .productType("MIS").build();
                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);

                if (!resp.isSuccess() || resp.orderId() == null || resp.orderId().isBlank()) {
                    log.warn("Auto-exec: {} {} order failed: {}", legKey, symbol, resp.message());
                    addLog("EXEC", "LEG_FAILED", legKey + " " + symbol + " " + side + ": " + resp.message());
                    cancelPendingLegs(account, adapter, placedLegs);
                    position.setStatus("FAILED");
                    position.setErrorMessage(legKey + " order failed: " + resp.message());
                    positionRepo.save(position);
                    addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike() + " — cancelled all legs");
                    return false;
                }

                placedLegs.add(new PlacedLeg(leg, resp.orderId(), resp.status()));
                if ("ce".equals(legKey)) position.setCeOrderId(resp.orderId());
                else if ("pe".equals(legKey)) position.setPeOrderId(resp.orderId());
                else if ("fut".equals(legKey)) position.setFutOrderId(resp.orderId());

                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }

            awaitFinalStatuses(account, adapter, placedLegs);
            List<PlacedLeg> filledLegs = placedLegs.stream()
                    .filter(leg -> isCompleteStatus(leg.status))
                    .toList();
            List<PlacedLeg> nonCompleteLegs = placedLegs.stream()
                    .filter(leg -> !isCompleteStatus(leg.status))
                    .toList();

            if (filledLegs.size() != orderPlan.size()) {
                cancelPendingLegs(account, adapter, nonCompleteLegs);
                squareOffFilledLegs(account, adapter, filledLegs);
                position.setStatus(filledLegs.isEmpty() ? "FAILED" : "PARTIAL");
                position.setErrorMessage("Only " + filledLegs.size() + "/" + orderPlan.size() + " legs filled. Pending orders cancelled and filled legs squared off.");
                positionRepo.save(position);
                addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike()
                        + " — only " + filledLegs.size() + "/" + orderPlan.size() + " legs filled; reverted trade");
                return false;
            }

            position.setStatus("OPEN");
            position.setEntryCost(BigDecimal.valueOf(estimateEntryCost(opp, lots)));
            positionRepo.save(position);

            addLog("EXEC", "SUCCESS", opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction()
                    + " | Lots=" + lots + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " | 3-leg box fully filled");
            return true;

        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
            return false;
        }
    }

    private void awaitFinalStatuses(BrokerAccount account, BrokerAdapter adapter, List<PlacedLeg> placedLegs) {
        for (int attempt = 0; attempt < 10; attempt++) {
            boolean allComplete = true;
            boolean anyTerminalFailure = false;
            for (PlacedLeg leg : placedLegs) {
                String latest = adapter.getOrderStatus(account.getAccessToken(), leg.orderId);
                if (latest != null && !latest.isBlank() && !"UNKNOWN".equalsIgnoreCase(latest)) {
                    leg.status = latest;
                }
                allComplete &= isCompleteStatus(leg.status);
                anyTerminalFailure |= isFailureStatus(leg.status);
            }
            if (allComplete || anyTerminalFailure) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void cancelPendingLegs(BrokerAccount account, BrokerAdapter adapter, List<PlacedLeg> legs) {
        for (PlacedLeg leg : legs) {
            if (leg.orderId == null || leg.orderId.isBlank() || isCompleteStatus(leg.status)) {
                continue;
            }
            try {
                adapter.cancelOrder(account.getAccessToken(), leg.orderId);
            } catch (Exception e) {
                log.warn("Failed to cancel {} {}: {}", leg.leg.legKey(), leg.orderId, e.getMessage());
            }
        }
    }

    private void squareOffFilledLegs(BrokerAccount account, BrokerAdapter adapter, List<PlacedLeg> legs) {
        for (PlacedLeg leg : legs) {
            BrokerOrderRequest.Side closeSide = leg.leg.side() == BrokerOrderRequest.Side.BUY
                    ? BrokerOrderRequest.Side.SELL
                    : BrokerOrderRequest.Side.BUY;
            BrokerOrderRequest closeReq = BrokerOrderRequest.builder()
                    .symbol(leg.leg.symbol())
                    .exchange("NFO")
                    .side(closeSide)
                    .quantity(leg.leg.quantity())
                    .price(0.0)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("MIS")
                    .build();
            try {
                BrokerOrderResponse closeResp = adapter.placeOrder(account.getAccessToken(), closeReq);
                addLog("EXEC", "SQUARE_OFF", leg.leg.legKey() + " " + leg.leg.symbol() + " -> "
                        + closeSide + " [" + closeResp.status() + "]");
            } catch (Exception e) {
                log.error("Failed to square off {} {}: {}", leg.leg.legKey(), leg.leg.symbol(), e.getMessage());
                addLog("EXEC", "SQUARE_OFF_FAILED", leg.leg.legKey() + " " + leg.leg.symbol() + ": " + e.getMessage());
            }
        }
    }

    private boolean isCompleteStatus(String status) {
        return "COMPLETE".equalsIgnoreCase(status) || "TRADED".equalsIgnoreCase(status);
    }

    private boolean isFailureStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        return normalized.contains("REJECT")
                || normalized.contains("CANCEL")
                || normalized.contains("EXPIRE")
                || normalized.contains("FAIL");
    }

    private double estimateEntryCost(OptionArbOpportunity opp, int lots) {
        int lotSize = getLotSize(opp.getUnderlying());
        double ce = opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0;
        double pe = opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0;
        double fut = opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0;
        return (ce + pe + fut) * lotSize * lots;
    }

    private double estimateHedgedMargin(String underlying, int lots) {
        // Hedged margin for box spread (FUT + CE + PE) — margin is the max of individual legs, not sum
        // With SEBI SPAN+Exposure, hedged NFO positions get ~60-70% margin benefit
        Map<String, Double> baseMargins = Map.of(
            "NIFTY", 120000.0,
            "BANKNIFTY", 200000.0,
            "MIDCPNIFTY", 150000.0,
            "FINNIFTY", 160000.0
        );
        double base = baseMargins.getOrDefault(underlying, 150000.0);
        return base * lots * 1.15; // 15% buffer for slippage
    }

    private int getLotSize(String underlying) {
        return switch (underlying) {
            case "NIFTY" -> 25;
            case "BANKNIFTY" -> 15;
            case "MIDCPNIFTY" -> 50;
            case "FINNIFTY" -> 25;
            default -> 25;
        };
    }

    private void addLog(String type, String status, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", System.currentTimeMillis());
        entry.put("time", LocalTime.now(ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("type", type);
        entry.put("status", status);
        entry.put("message", message);
        execLogs.add(entry);
        if (execLogs.size() > 200) execLogs.remove(0);
    }

    private record PlannedLeg(String symbol, BrokerOrderRequest.Side side, int quantity, double price, String legKey) {}

    private static final class PlacedLeg {
        private final PlannedLeg leg;
        private final String orderId;
        private String status;

        private PlacedLeg(PlannedLeg leg, String orderId, String status) {
            this.leg = leg;
            this.orderId = orderId;
            this.status = status;
        }
    }
}
