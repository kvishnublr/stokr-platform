package com.stokr.arbitrage;

import com.stokr.broker.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bid-parity / option-arb auto execution (broker-routed).
 * Persists settings to option_arb_auto_exec_settings and executes 3-leg hedges (CE+PE+FUT).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionArbAutoExecService {

    private static final Map<String, Double> HEDGED_MARGIN = Map.of(
            "NIFTY", 150000.0,
            "BANKNIFTY", 250000.0,
            "MIDCPNIFTY", 180000.0,
            "FINNIFTY", 200000.0
    );

    private final OptionArbOpportunityRepository oppRepo;
    private final LivePositionRepository positionRepo;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;
    private final OptionChainService optionChainService;
    private final AutoExecSettingRepository settingsRepo;

    private final ConcurrentHashMap<String, Object> settings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> execLogs = Collections.synchronizedList(new ArrayList<>());

    @PostConstruct
    public void init() {
        applyDefaults();
        loadFromDb();
        log.info("Loaded {} auto-exec settings from DB (enabled={})", settings.size(), settings.get("enabled"));
    }

    private void applyDefaults() {
        settings.put("enabled", false);
        settings.put("broker", "NAVIA");
        settings.put("niftyEnabled", false);
        settings.put("niftyMinEdge", 2000.0);
        settings.put("niftyLots", 1);
        settings.put("bankniftyEnabled", false);
        settings.put("bankniftyMinEdge", 2000.0);
        settings.put("bankniftyLots", 1);
        settings.put("finniftyEnabled", false);
        settings.put("finniftyMinEdge", 2000.0);
        settings.put("finniftyLots", 1);
        settings.put("midcpniftyEnabled", false);
        settings.put("midcpniftyMinEdge", 2000.0);
        settings.put("midcpniftyLots", 1);
        settings.put("maxOpenPositions", 3);
        settings.put("maxDailyLoss", 5000.0);
        settings.put("strategyFilter", "PARITY");
    }

    private void loadFromDb() {
        try {
            for (AutoExecSetting row : settingsRepo.findAllByOrderBySettingKey()) {
                parseAndPut(row.getSettingKey(), row.getSettingValue());
            }
        } catch (Exception e) {
            log.warn("Could not load auto-exec settings from DB: {}", e.getMessage());
        }
    }

    private void parseAndPut(String key, String value) {
        if (key == null || value == null) return;
        if ("enabled".equals(key) || key.endsWith("Enabled")) {
            settings.put(key, Boolean.parseBoolean(value));
        } else if (key.endsWith("MinEdge") || "maxDailyLoss".equals(key)) {
            try { settings.put(key, Double.parseDouble(value)); } catch (Exception ignored) {}
        } else if (key.endsWith("Lots") || "maxOpenPositions".equals(key)) {
            try { settings.put(key, Integer.parseInt(value)); } catch (Exception ignored) {}
        } else {
            settings.put(key, value);
        }
    }

    public Map<String, Object> getSettings() {
        return new LinkedHashMap<>(settings);
    }

    @Transactional
    public void updateSetting(String key, String value) {
        parseAndPut(key, value);
        AutoExecSetting row = settingsRepo.findBySettingKey(key).orElseGet(AutoExecSetting::new);
        row.setSettingKey(key);
        row.setSettingValue(value);
        settingsRepo.save(row);
        addLog("SETTINGS", "INFO", "Updated '" + key + "' = " + value);
    }

    public List<Map<String, Object>> getExecLogs() {
        List<Map<String, Object>> list = new ArrayList<>(execLogs);
        Collections.reverse(list);
        return list.stream().limit(100).toList();
    }

    /**
     * Called after scan saves new opportunities.
     */
    public void evaluateAndExecute(List<OptionArbOpportunity> newOpps) {
        if (!Boolean.TRUE.equals(settings.get("enabled"))) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 16)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        String broker = String.valueOf(settings.getOrDefault("broker", "NAVIA"));
        int maxPositions = ((Number) settings.getOrDefault("maxOpenPositions", 3)).intValue();
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
            if (accounts.isEmpty()) {
                addLog("BROKER", "ERROR", "No ACTIVE " + broker + " account for user " + userId);
                return;
            }
            account = accounts.get(0);
            adapter = brokerService.getAdapter(broker);
        } catch (Exception e) {
            log.error("Auto-exec: broker setup failed: {}", e.getMessage());
            return;
        }

        double availableMargin;
        try {
            BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
            availableMargin = margin != null ? margin.doubleValue() : 0;
        } catch (Exception e) {
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

        String strategyFilter = String.valueOf(settings.getOrDefault("strategyFilter", "PARITY"));

        List<OptionArbOpportunity> ranked = newOpps.stream()
                .filter(o -> o.getUnderlying() != null && o.getEdgeAfterCosts() != null && o.getStrike() != null)
                .sorted((a, b) -> Double.compare(
                        b.getEdgeAfterCosts().doubleValue(),
                        a.getEdgeAfterCosts().doubleValue()))
                .toList();

        for (OptionArbOpportunity opp : ranked) {
            if (currentOpen >= maxPositions) break;

            String key = opp.getUnderlying().toLowerCase();
            if (!Boolean.TRUE.equals(settings.get(key + "Enabled"))) continue;

            String stratType = opp.getStrategyType() != null ? opp.getStrategyType().toUpperCase() : "";
            if ("PARITY".equalsIgnoreCase(strategyFilter)
                    && !stratType.contains("PARITY") && !stratType.contains("BID")) continue;
            if ("BOX".equalsIgnoreCase(strategyFilter) && !stratType.contains("BOX")) continue;

            double minEdge = ((Number) settings.getOrDefault(key + "MinEdge", 2000.0)).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() < minEdge) continue;
            if (opp.getExpiryDate() == null) continue;

            String action = normalizeAction(opp.getAction());
            if (action == null) {
                addLog("SIGNAL", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " unsupported action: " + opp.getAction());
                continue;
            }

            if (positionRepo.findByUserIdAndStatusOrderByEnteredAtDesc(userId, "OPEN").stream()
                    .anyMatch(p -> Objects.equals(opp.getId(), p.getOpportunityId())
                            || (Objects.equals(p.getUnderlying(), opp.getUnderlying())
                            && Objects.equals(p.getStrike(), opp.getStrike())
                            && "OPEN".equals(p.getStatus())))) {
                continue;
            }

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            double required = estimateHedgedMargin(opp.getUnderlying(), lots);
            if (required > availableMargin * 0.9) {
                addLog("MARGIN", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " needs ₹" + String.format("%.0f", required)
                        + " but only ₹" + String.format("%.0f", availableMargin) + " available");
                continue;
            }

            addLog("SIGNAL", "FIRING", opp.getUnderlying() + " " + opp.getStrike()
                    + " " + action + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " ≥ ₹" + String.format("%.0f", minEdge));
            executeTrade(account, adapter, opp, lots, userId, action);
            currentOpen++;
            availableMargin -= required;
        }
    }

    /** Map legacy labels to CONVERSION / REVERSAL. */
    static String normalizeAction(String raw) {
        if (raw == null) return null;
        String a = raw.toUpperCase(Locale.ROOT);
        if (a.contains("CONVERSION") || a.contains("BUY CE / SELL PE") || a.equals("BUY CE+PE / SELL FUT")) {
            // Legacy "BUY CE+PE / SELL FUT" was mislabeled; true conversion is BUY CE / SELL PE / SELL FUT
            if (a.equals("BUY CE+PE / SELL FUT")) {
                // Old buggy label that meant sell-synth in some paths — refuse ambiguous legacy strings for live fire
                // Prefer explicit CONVERSION/REVERSAL from fixed scanner.
                return null;
            }
            return "CONVERSION";
        }
        if (a.contains("REVERSAL") || a.contains("SELL CE / BUY PE") || a.equals("BUY FUT / SELL CE+PE")) {
            if (a.equals("BUY FUT / SELL CE+PE")) return null; // ambiguous legacy
            return "REVERSAL";
        }
        if (a.contains("BUY CE") && a.contains("SELL PE") && a.contains("SELL") && a.contains("FUT")) return "CONVERSION";
        if (a.contains("SELL CE") && a.contains("BUY PE") && a.contains("BUY") && a.contains("FUT")) return "REVERSAL";
        return null;
    }

    private void executeTrade(BrokerAccount account, BrokerAdapter adapter, OptionArbOpportunity opp,
                              int lots, Long userId, String action) {
        int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
        LocalDate expiry = opp.getExpiryDate();
        String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), expiry, opp.getStrike(), "CE");
        String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), expiry, opp.getStrike(), "PE");
        String futSymbol = buildMonthlyFutSymbol(opp.getUnderlying());

        LivePosition position = LivePosition.builder()
                .userId(userId)
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(action)
                .strategyType(opp.getStrategyType())
                .ceSymbol(ceSymbol)
                .peSymbol(peSymbol)
                .futSymbol(futSymbol)
                .lots(lots)
                .lotSize(lotSize)
                .ceEntryPrice(opp.getCeEntryPrice())
                .peEntryPrice(opp.getPeEntryPrice())
                .targetEdge(opp.getEdgeAfterCosts())
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        boolean conversion = "CONVERSION".equals(action);
        int qty = lots * lotSize;
        String token = account.getAccessToken();

        try {
            // Leg 1 CE
            BrokerOrderResponse ceResp = place(adapter, token, ceSymbol,
                    conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL, qty);
            if (!isPlaced(ceResp)) {
                position.setStatus("FAILED");
                position.setErrorMessage("CE failed: " + (ceResp != null ? ceResp.message() : "null"));
                positionRepo.save(position);
                addLog("EXEC", "FAILED", opp.getUnderlying() + " CE: " + (ceResp != null ? ceResp.message() : "null"));
                return;
            }
            position.setCeOrderId(ceResp.orderId());

            // Leg 2 PE
            BrokerOrderResponse peResp = place(adapter, token, peSymbol,
                    conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY, qty);
            if (!isPlaced(peResp)) {
                // Attempt square-off CE
                place(adapter, token, ceSymbol,
                        conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY, qty);
                position.setStatus("PARTIAL");
                position.setErrorMessage("PE failed: " + (peResp != null ? peResp.message() : "null") + " (CE squared)");
                positionRepo.save(position);
                addLog("EXEC", "PARTIAL", opp.getUnderlying() + " PE failed, CE squared");
                return;
            }
            position.setPeOrderId(peResp.orderId());

            // Leg 3 FUT — required for hedge
            BrokerOrderResponse futResp = place(adapter, token, futSymbol,
                    conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY, qty);
            if (!isPlaced(futResp)) {
                // Square CE+PE
                place(adapter, token, ceSymbol,
                        conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY, qty);
                place(adapter, token, peSymbol,
                        conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL, qty);
                position.setStatus("PARTIAL");
                position.setErrorMessage("FUT failed: " + (futResp != null ? futResp.message() : "null") + " (options squared)");
                positionRepo.save(position);
                addLog("EXEC", "PARTIAL", opp.getUnderlying() + " FUT failed, options squared");
                return;
            }
            position.setFutOrderId(futResp.orderId());
            position.setStatus("OPEN");
            positionRepo.save(position);

            addLog("EXEC", "SUCCESS", opp.getUnderlying() + " " + opp.getStrike() + " " + action
                    + " lots=" + lots
                    + " CE:" + ceResp.orderId() + " PE:" + peResp.orderId() + " FUT:" + futResp.orderId());
        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
        }
    }

    private BrokerOrderResponse place(BrokerAdapter adapter, String token, String symbol,
                                      BrokerOrderRequest.Side side, int qty) {
        BrokerOrderRequest req = BrokerOrderRequest.builder()
                .symbol(symbol)
                .exchange("NFO")
                .side(side)
                .quantity(qty)
                .price(0.0)
                .orderType(BrokerOrderRequest.OrderType.MARKET)
                .productType("NRML")
                .build();
        return adapter.placeOrder(token, req);
    }

    private boolean isPlaced(BrokerOrderResponse resp) {
        return resp != null && resp.orderId() != null && !resp.orderId().isBlank()
                && !"REJECTED".equalsIgnoreCase(resp.status())
                && !"ERROR".equalsIgnoreCase(resp.status());
    }

    private double estimateHedgedMargin(String underlying, int lots) {
        return HEDGED_MARGIN.getOrDefault(underlying, 200000.0) * 1.15 * Math.max(1, lots);
    }

    private String buildMonthlyFutSymbol(String underlying) {
        LocalDate monthly = optionChainService.getMonthlyExpiry(underlying);
        int yy = monthly.getYear() % 100;
        String mon = monthly.getMonth().name().substring(0, 3);
        return String.format("%s%02d%sFUT", underlying.replace(" ", ""), yy, mon);
    }

    private void addLog(String type, String status, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", System.currentTimeMillis());
        entry.put("time", LocalTime.now(ZoneId.of("Asia/Kolkata"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("type", type);
        entry.put("status", status);
        entry.put("message", message);
        execLogs.add(entry);
        if (execLogs.size() > 200) execLogs.remove(0);
        log.info("AutoExec [{}]/{}] {}", type, status, message);
    }
}
