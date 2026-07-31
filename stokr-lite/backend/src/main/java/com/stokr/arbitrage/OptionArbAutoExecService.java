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
            double entryCost = estimateEntryCost(opp, lots);
            if (entryCost > availableMargin * 0.9) {
                addLog("MARGIN", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " needs ₹" + String.format("%.0f", entryCost)
                        + " but only ₹" + String.format("%.0f", availableMargin) + " available");
                continue;
            }

            addLog("SIGNAL", "FIRING", opp.getUnderlying() + " " + opp.getStrike()
                    + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " > threshold ₹" + String.format("%.0f", minEdge) + " — executing NOW");
            executeTrade(account, adapter, opp, lots, userId);
            currentOpen++;
            availableMargin -= entryCost;
        }
    }

    private void executeTrade(BrokerAccount account, BrokerAdapter adapter, OptionArbOpportunity opp, int lots, Long userId) {
        int lotSize = getLotSize(opp.getUnderlying());

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
                .targetEdge(opp.getEdgeAfterCosts())
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
            String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
            position.setCeSymbol(ceSymbol);
            position.setPeSymbol(peSymbol);

            boolean isBuyCE = opp.getAction() != null && opp.getAction().toUpperCase().contains("BUY CE");

            int ceQty = lots * lotSize;
            int peQty = lots * lotSize;

            BrokerOrderRequest ceReq = BrokerOrderRequest.builder()
                    .symbol(ceSymbol).exchange("NFO")
                    .side(isBuyCE ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL)
                    .quantity(ceQty).price(0.0)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("MIS").build();
            BrokerOrderResponse ceResp = adapter.placeOrder(account.getAccessToken(), ceReq);

            if (!ceResp.isSuccess()) {
                position.setStatus("FAILED");
                position.setErrorMessage("CE order failed: " + ceResp.message());
                positionRepo.save(position);
                addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike() + " CE: " + ceResp.message());
                return;
            }
            position.setCeOrderId(ceResp.orderId());

            BrokerOrderRequest peReq = BrokerOrderRequest.builder()
                    .symbol(peSymbol).exchange("NFO")
                    .side(isBuyCE ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY)
                    .quantity(peQty).price(0.0)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("MIS").build();
            BrokerOrderResponse peResp = adapter.placeOrder(account.getAccessToken(), peReq);

            if (!peResp.isSuccess()) {
                position.setStatus("PARTIAL");
                position.setErrorMessage("PE order failed: " + peResp.message() + " (CE placed: " + ceResp.orderId() + ")");
                positionRepo.save(position);
                addLog("EXEC", "PARTIAL", opp.getUnderlying() + " " + opp.getStrike() + " PE failed: " + peResp.message());
                return;
            }
            position.setPeOrderId(peResp.orderId());
            position.setStatus("OPEN");
            positionRepo.save(position);

            addLog("EXEC", "SUCCESS", opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction()
                    + " | Lots=" + lots + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " | CE:" + ceResp.orderId() + " PE:" + peResp.orderId());

        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
        }
    }

    private double estimateEntryCost(OptionArbOpportunity opp, int lots) {
        int lotSize = getLotSize(opp.getUnderlying());
        double ce = opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0;
        double pe = opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0;
        return (ce + pe) * lotSize * lots;
    }

    private int getLotSize(String underlying) {
        return switch (underlying) {
            case "BANKNIFTY" -> 15;
            case "MIDCPNIFTY" -> 120;
            case "FINNIFTY" -> 60;
            default -> 50;
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
}
