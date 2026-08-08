package com.stokr.arbitrage;

import com.stokr.broker.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final AutoExecSettingRepository autoExecSettingRepo;

    private final List<Map<String, Object>> execLogs = Collections.synchronizedList(new ArrayList<>());

    @PostConstruct
    public void init() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", true);
        defaults.put("broker", "NAVIA");
        defaults.put("niftyEnabled", true);
        defaults.put("niftyMinEdge", 500.0);
        defaults.put("niftyLots", 1);
        defaults.put("bankniftyEnabled", true);
        defaults.put("bankniftyMinEdge", 500.0);
        defaults.put("bankniftyLots", 1);
        defaults.put("finniftyEnabled", true);
        defaults.put("finniftyMinEdge", 500.0);
        defaults.put("finniftyLots", 1);
        defaults.put("midcpniftyEnabled", true);
        defaults.put("midcpniftyMinEdge", 500.0);
        defaults.put("midcpniftyLots", 1);
        defaults.put("maxOpenPositions", 1);
        defaults.put("maxDailyLoss", 5000.0);
        defaults.put("stopLossEnabled", true);
        defaults.put("stopLossPct", 50.0);
        defaults.put("rolloverEnabled", true);
        defaults.put("rolloverThresholdPct", 90.0);
        defaults.put("autoExitEnabled", true);
        defaults.put("autoExitThresholdPct", 90.0);
        defaults.put("strategyFilter", "ALL");

        // Load persisted settings from DB, overlay on defaults
        try {
            List<AutoExecSetting> dbSettings = autoExecSettingRepo.findAll();
            for (AutoExecSetting s : dbSettings) {
                String key = s.getSettingKey();
                String val = s.getSettingValue();
                if ("enabled".equals(key)) defaults.put(key, Boolean.parseBoolean(val));
                else if (key.endsWith("Enabled")) defaults.put(key, Boolean.parseBoolean(val));
                else if (key.endsWith("MinEdge") || key.equals("maxDailyLoss") || key.equals("rolloverThresholdPct") || key.equals("autoExitThresholdPct"))
                    defaults.put(key, Double.parseDouble(val));
                else if (key.endsWith("Lots") || key.equals("maxOpenPositions")) defaults.put(key, Integer.parseInt(val));
                else defaults.put(key, val);
            }
            log.info("Loaded {} auto-exec settings from DB", dbSettings.size());
        } catch (Exception e) {
            log.debug("Failed to load auto-exec settings from DB: {}", e.getMessage());
        }

        autoExecSettings.put("global", defaults);
    }

    public Map<String, Object> getSettings() {
        return new LinkedHashMap<>(autoExecSettings.getOrDefault("global", Map.of()));
    }

    public void updateSetting(String key, String value) {
        Map<String, Object> s = autoExecSettings.computeIfAbsent("global", k -> new LinkedHashMap<>());
        if ("enabled".equals(key)) s.put("enabled", Boolean.parseBoolean(value));
        else if (key.endsWith("Enabled")) s.put(key, Boolean.parseBoolean(value));
        else if (key.endsWith("MinEdge") || key.equals("maxDailyLoss") || key.equals("rolloverThresholdPct") || key.equals("autoExitThresholdPct")) s.put(key, Double.parseDouble(value));
        else if (key.endsWith("Lots") || key.equals("maxOpenPositions")) s.put(key, Integer.parseInt(value));
        else s.put(key, value);

        // Persist to DB
        try {
            AutoExecSetting dbSetting = autoExecSettingRepo.findBySettingKey(key)
                    .orElse(new AutoExecSetting());
            dbSetting.setSettingKey(key);
            dbSetting.setSettingValue(String.valueOf(s.get(key)));
            autoExecSettingRepo.save(dbSetting);
        } catch (Exception e) {
            log.error("Failed to persist setting {} to DB: {}", key, e.getMessage());
        }
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
    public void evaluateAndExecuteFromMaps(List<Map<String, Object>> scanResults) {
        List<OptionArbOpportunity> opps = new ArrayList<>();
        for (Map<String, Object> m : scanResults) {
            try {
                OptionArbOpportunity opp = new OptionArbOpportunity();
                opp.setUnderlying((String) m.get("underlying"));
                opp.setStrike(m.get("strike") instanceof Number n ? n.intValue() : null);
                opp.setAction((String) m.get("action"));
                opp.setStrategyType((String) m.get("strategyType"));
                opp.setExpiryDate(m.get("expiryDate") instanceof String s ? LocalDate.parse(s) : null);
                Object eac = m.get("edgeAfterCosts");
                if (eac instanceof Number n) opp.setEdgeAfterCosts(BigDecimal.valueOf(n.doubleValue()));
                Object ce = m.get("ceEntryPrice");
                if (ce instanceof Number n) opp.setCeEntryPrice(BigDecimal.valueOf(n.doubleValue()));
                Object pe = m.get("peEntryPrice");
                if (pe instanceof Number n) opp.setPeEntryPrice(BigDecimal.valueOf(n.doubleValue()));
                Object fut = m.get("futuresPrice");
                if (fut instanceof Number n) opp.setFuturesPrice(BigDecimal.valueOf(n.doubleValue()));
                Object id = m.get("id");
                if (id instanceof Number n) opp.setId(n.longValue());
                opps.add(opp);
            } catch (Exception ignored) {}
        }
        if (!opps.isEmpty()) evaluateAndExecute(opps);
    }

    public synchronized void evaluateAndExecute(List<OptionArbOpportunity> newOpps) {
        Map<String, Object> settings = getSettings();
        if (!Boolean.TRUE.equals(settings.get("enabled"))) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        int maxPositions = (int) settings.getOrDefault("maxOpenPositions", 1);
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
        double todayPnl = 0;
        try {
            List<LivePosition> openPos = positionRepo.findAllOpen();
            if (!openPos.isEmpty()) {
                List<String> syms = new ArrayList<>();
                for (LivePosition p : openPos) {
                    if (p.getCeSymbol() != null) syms.add(p.getCeSymbol());
                    if (p.getPeSymbol() != null) syms.add(p.getPeSymbol());
                    if (p.getFutSymbol() != null) syms.add(p.getFutSymbol());
                }
                Map<String, OptionChainService.OptionQuote> q = syms.isEmpty() ? Map.of() : optionChainService.fetchQuotes(syms);
                for (LivePosition p : openPos) {
                    todayPnl += computePnl(p, q);
                }
            }
        } catch (Exception e) {
            log.debug("Daily loss quote fetch failed, using stored values: {}", e.getMessage());
            todayPnl = positionRepo.findAllOpen().stream()
                    .filter(p -> p.getCurrentPnl() != null)
                    .mapToDouble(p -> p.getCurrentPnl().doubleValue())
                    .sum();
        }
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

    /**
     * Check all open positions for roll-over.
     * When live P&L reaches rolloverThresholdPct% of target edge, square off and open new position.
     */
    @Scheduled(fixedDelayString = "30000", initialDelay = 30000)
    public synchronized void checkRollover() {
        Map<String, Object> settings = getSettings();
        boolean rolloverEnabled = Boolean.TRUE.equals(settings.get("rolloverEnabled"));
        boolean autoExitEnabled = Boolean.TRUE.equals(settings.get("autoExitEnabled"));
        if (!rolloverEnabled && !autoExitEnabled) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        double rolloverThresholdPct = ((Number) settings.getOrDefault("rolloverThresholdPct", 90.0)).doubleValue();
        double autoExitThresholdPct = ((Number) settings.getOrDefault("autoExitThresholdPct", 90.0)).doubleValue();

        List<LivePosition> openPositions = positionRepo.findAllOpen().stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .toList();
        if (openPositions.isEmpty()) return;

        List<String> symbols = new ArrayList<>();
        for (LivePosition p : openPositions) {
            if (p.getCeSymbol() != null) symbols.add(p.getCeSymbol());
            if (p.getPeSymbol() != null) symbols.add(p.getPeSymbol());
            if (p.getFutSymbol() != null) symbols.add(p.getFutSymbol());
        }
        if (symbols.isEmpty()) return;

        Map<String, OptionChainService.OptionQuote> quotes;
        try {
            quotes = optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            log.debug("Rollover quote fetch failed: {}", e.getMessage());
            return;
        }

        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);
        Long userId = null;
        BrokerAccount account = null;
        BrokerAdapter adapter = null;
        if (!isPaper) {
            try {
                userId = brokerAccountRepo.findByStatus("ACTIVE").stream()
                        .findFirst().map(BrokerAccount::getUserId).orElse(null);
                if (userId == null) return;
                List<BrokerAccount> accounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, broker, "ACTIVE");
                if (accounts.isEmpty()) return;
                account = accounts.get(0);
                adapter = brokerService.getAdapter(broker);
            } catch (Exception e) {
                log.error("Rollover: broker setup failed: {}", e.getMessage());
                return;
            }
        }

        for (LivePosition pos : openPositions) {
            double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
            if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) ceCurrent = quotes.get(pos.getCeSymbol()).lastPrice;
            if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) peCurrent = quotes.get(pos.getPeSymbol()).lastPrice;
            if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) futCurrent = quotes.get(pos.getFutSymbol()).lastPrice;

            double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
            double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
            double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
            int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
            int lots = pos.getLots() != null ? pos.getLots() : 1;
            double targetEdge = pos.getTargetEdge() != null ? pos.getTargetEdge().doubleValue() : 0;

            if (targetEdge <= 0) continue;

            double pnl = 0;
            String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
            if (ceCurrent > 0 || peCurrent > 0 || futCurrent > 0) {
                if (action.contains("BUY CE+PE")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                } else if (action.contains("SELL CE+PE")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                    if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                    if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
                } else {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                }
            }
            pnl *= lotSize * lots;

            double pnlPerLot = lots > 0 ? pnl / lots : 0;
            double pctAchieved = targetEdge > 0 ? (pnlPerLot / targetEdge) * 100 : 0;

            boolean shouldExit = false;
            String exitReason = "";

            boolean stopLossEnabled = Boolean.TRUE.equals(settings.get("stopLossEnabled"));
            double stopLossPct = ((Number) settings.getOrDefault("stopLossPct", 50.0)).doubleValue();

            // Stop-loss: close position if loss exceeds threshold
            if (stopLossEnabled && targetEdge > 0 && pnlPerLot < 0) {
                double lossPct = Math.abs(pnlPerLot / targetEdge) * 100;
                if (lossPct >= stopLossPct) {
                    shouldExit = true;
                    exitReason = "STOP_LOSS";
                    log.info("STOP_LOSS: {} {} strike {} — loss {}% exceeds threshold {}% (P&L ₹{})",
                            pos.getUnderlying(), pos.getAction(), pos.getStrike(), String.format("%.0f", lossPct),
                            String.format("%.0f", stopLossPct), String.format("%.0f", pnl));
                    addLog("STOP_LOSS", "TRIGGERED", pos.getUnderlying() + " " + pos.getStrike()
                            + " — loss " + String.format("%.0f", lossPct) + "% (₹" + String.format("%.0f", pnl) + ")");
                }
            }

            // Auto-exit: close position when target edge met (no re-entry)
            if (autoExitEnabled && pctAchieved >= autoExitThresholdPct) {
                shouldExit = true;
                exitReason = "AUTO_EXIT";
                log.info("AUTO_EXIT: {} {} strike {} — {}% of target ₹{} reached (P&L ₹{})",
                        pos.getUnderlying(), pos.getAction(), pos.getStrike(), String.format("%.0f", pctAchieved),
                        String.format("%.0f", targetEdge), String.format("%.0f", pnl));
                addLog("AUTO_EXIT", "TRIGGERED", pos.getUnderlying() + " " + pos.getStrike()
                        + " — " + String.format("%.0f", pctAchieved) + "% of target reached (₹" + String.format("%.0f", pnl) + ")");
            }
            // Roll-over: close + re-enter new position
            else if (rolloverEnabled && pctAchieved >= rolloverThresholdPct) {
                shouldExit = true;
                exitReason = "ROLLOVER";
                log.info("ROLLOVER: {} {} strike {} — {}% of target ₹{} reached (P&L ₹{}). Rolling over...",
                        pos.getUnderlying(), pos.getAction(), pos.getStrike(), String.format("%.0f", pctAchieved),
                        String.format("%.0f", targetEdge), String.format("%.0f", pnl));
                addLog("ROLLOVER", "TRIGGERED", pos.getUnderlying() + " " + pos.getStrike()
                        + " — " + String.format("%.0f", pctAchieved) + "% of target reached (₹" + String.format("%.0f", pnl) + ")");
            }

            if (!shouldExit) continue;

            // Square off existing position
            boolean squaredOff;
            if (isPaper) {
                squaredOff = true;
                addLog(exitReason, "SQUARED_OFF", pos.getUnderlying() + " " + pos.getStrike()
                        + " — PAPER mode, closing at market price (P&L ₹" + String.format("%.0f", pnl) + ")");
            } else {
                squaredOff = squareOffPosition(account, adapter, pos);
            }
            if (!squaredOff) {
                addLog(exitReason, "SQUAREOFF_FAILED", "Failed to square off " + pos.getUnderlying() + " " + pos.getStrike());
                continue;
            }

            // Save exit prices and close position
            pos.setStatus("CLOSED");
            pos.setExitedAt(LocalDateTime.now());
            pos.setCurrentPnl(BigDecimal.valueOf(pnl));
            pos.setCeExitPrice(BigDecimal.valueOf(ceCurrent));
            pos.setPeExitPrice(BigDecimal.valueOf(peCurrent));
            pos.setFutExitPrice(BigDecimal.valueOf(futCurrent));
            positionRepo.save(pos);

            // Update opportunity with exit time + P&L
            if (pos.getOpportunityId() != null) {
                try {
                    var oppOpt = oppRepo.findById(pos.getOpportunityId());
                    if (oppOpt.isPresent()) {
                        var oppEntity = oppOpt.get();
                        oppEntity.setStatus("EXITED");
                        oppEntity.setExitTime(LocalDateTime.now());
                        oppEntity.setPnlAfterCosts(pos.getCurrentPnl());
                        oppEntity.setCeExitPrice(BigDecimal.valueOf(ceCurrent));
                        oppEntity.setPeExitPrice(BigDecimal.valueOf(peCurrent));
                        oppRepo.save(oppEntity);
                    }
                } catch (Exception e) {
                    log.debug("Failed to update opportunity status on close: {}", e.getMessage());
                }
            }

            addLog(exitReason, "SQUARED_OFF", pos.getUnderlying() + " " + pos.getStrike() + " — P&L ₹" + String.format("%.0f", pnl));

            // Only re-enter on rollover, not on auto-exit
            if ("ROLLOVER".equals(exitReason)) {
                addLog("ROLLOVER", "NEW_ENTRY", "Looking for new entry for " + pos.getUnderlying());
            }
        }
    }

    private boolean squareOffPosition(BrokerAccount account, BrokerAdapter adapter, LivePosition pos) {
        try {
            int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
            int lots = pos.getLots() != null ? pos.getLots() : 1;
            int qty = lotSize * lots;
            String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";

            List<PlannedLeg> closePlan;
            if (action.contains("BUY CE+PE")) {
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "ce"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "pe"),
                    new PlannedLeg(pos.getFutSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "fut")
                );
            } else if (action.contains("SELL CE+PE")) {
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "ce"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "pe"),
                    new PlannedLeg(pos.getFutSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "fut")
                );
            } else {
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "ce"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "pe"),
                    new PlannedLeg(pos.getFutSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "fut")
                );
            }

            for (PlannedLeg leg : closePlan) {
                BrokerOrderRequest req = BrokerOrderRequest.builder()
                        .symbol(leg.symbol()).exchange("NFO")
                        .side(leg.side()).quantity(leg.quantity())
                        .price(leg.price())
                        .orderType(BrokerOrderRequest.OrderType.MARKET)
                        .productType("MIS").build();
                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);
                if (!resp.isSuccess()) {
                    addLog("ROLLOVER", "LEG_FAIL", leg.legKey() + " " + leg.symbol() + ": " + resp.message());
                }
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            return true;
        } catch (Exception e) {
            log.error("Rollover square-off failed: {}", e.getMessage());
            return false;
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
                .targetEdge(BigDecimal.valueOf(recalculateTargetEdge(
                    opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0,
                    opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0,
                    opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0,
                    opp.getStrike(), opp.getAction(), opp.getUnderlying())))
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

            // BUY-first: place BUY legs first, then hedge with sell legs.
            // CONVERSION: BUY CE, SELL FUT, SELL PE
            // REVERSAL: BUY PE, BUY FUT, SELL CE
            List<PlannedLeg> orderPlan;
            if (isConversion) {
                orderPlan = List.of(
                    new PlannedLeg(ceSymbol, BrokerOrderRequest.Side.BUY, ceQty, 0.0, "ce"),
                    new PlannedLeg(futSymbol, BrokerOrderRequest.Side.SELL, futQty, 0.0, "fut"),
                    new PlannedLeg(peSymbol, BrokerOrderRequest.Side.SELL, peQty, 0.0, "pe")
                );
            } else {
                orderPlan = List.of(
                    new PlannedLeg(peSymbol, BrokerOrderRequest.Side.BUY, peQty, 0.0, "pe"),
                    new PlannedLeg(futSymbol, BrokerOrderRequest.Side.BUY, futQty, 0.0, "fut"),
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
            boolean anyUnknown = false;
            for (PlacedLeg leg : placedLegs) {
                String latest = adapter.getOrderStatus(account.getAccessToken(), leg.orderId);
                if (latest != null && !latest.isBlank() && !"UNKNOWN".equalsIgnoreCase(latest)) {
                    leg.status = latest;
                } else {
                    anyUnknown = true;
                }
                allComplete &= isCompleteStatus(leg.status);
                anyTerminalFailure |= isFailureStatus(leg.status);
            }
            log.info("awaitFinalStatuses attempt {}: allComplete={} anyTerminalFailure={} anyUnknown={} statuses={}",
                    attempt, allComplete, anyTerminalFailure, anyUnknown,
                    placedLegs.stream().map(l -> l.leg.legKey() + "=" + l.status).toList());
            if (allComplete || anyTerminalFailure) {
                return;
            }
            if (anyUnknown && attempt >= 3) {
                log.warn("Navia OrderBook auth failing after {} polls — orders likely filled on exchange, marking all as COMPLETE", attempt + 1);
                for (PlacedLeg leg : placedLegs) {
                    if (!isCompleteStatus(leg.status) && !isFailureStatus(leg.status)) {
                        leg.status = "COMPLETE";
                    }
                }
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

    private double recalculateTargetEdge(double ceEntry, double peEntry, double futEntry, int strike, String action, String underlying) {
        if (ceEntry <= 0 || peEntry <= 0 || futEntry <= 0) return 0;

        double synthetic = ceEntry - peEntry + strike;
        double parityDev = Math.abs(futEntry - synthetic);

        double grossEdge = parityDev * getLotSize(underlying);
        double stt = grossEdge * 0.001;
        double brokerage = 120.0;
        double exchange = grossEdge * 0.000345 * 6;
        double sebi = grossEdge * 0.00001;
        double gst = (brokerage + exchange + sebi) * 0.18;
        double ipft = grossEdge * 0.00001;
        double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;
        return Math.max(0, grossEdge - totalCosts);
    }

    private double computePnl(LivePosition pos, Map<String, OptionChainService.OptionQuote> quotes) {
        double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
        if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) ceCurrent = quotes.get(pos.getCeSymbol()).lastPrice;
        if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) peCurrent = quotes.get(pos.getPeSymbol()).lastPrice;
        if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) futCurrent = quotes.get(pos.getFutSymbol()).lastPrice;
        double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
        double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
        double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : 25;
        int lots = pos.getLots() != null ? pos.getLots() : 1;
        String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
        double pnl = 0;
        if (ceCurrent > 0 || peCurrent > 0 || futCurrent > 0) {
            if (action.contains("BUY CE+PE")) {
                if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
            } else if (action.contains("SELL CE+PE")) {
                if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
            } else {
                if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
            }
        }
        return pnl * lotSize * lots;
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
