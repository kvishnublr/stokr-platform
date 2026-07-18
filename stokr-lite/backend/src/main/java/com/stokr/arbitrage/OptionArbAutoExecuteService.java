package com.stokr.arbitrage;

import com.stokr.broker.BrokerOrderRequest;
import com.stokr.broker.BrokerOrderResponse;
import com.stokr.broker.ZerodhaAdapter;
import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OptionArbAutoExecuteService {

    private static final Logger log = LoggerFactory.getLogger(OptionArbAutoExecuteService.class);
    private static final double MIN_MARGIN_BUFFER = 1.15;

    private static final Map<String, Double> HEDGED_MARGIN = Map.of(
        "NIFTY", 150000.0,
        "BANKNIFTY", 250000.0,
        "MIDCPNIFTY", 180000.0,
        "FINNIFTY", 200000.0
    );

    private final AutoExecSettingRepository settingsRepo;
    private final ExecutedTradeRepository tradeRepo;
    private final ZerodhaAdapter zerodhaAdapter;
    private final ZerodhaTokenManager tokenManager;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private final ConcurrentHashMap<String, Long> lastExecTime = new ConcurrentHashMap<>();
    private static final long EXEC_COOLDOWN_MS = 60_000;

    public OptionArbAutoExecuteService(AutoExecSettingRepository settingsRepo,
                                        ExecutedTradeRepository tradeRepo,
                                        ZerodhaAdapter zerodhaAdapter,
                                        ZerodhaTokenManager tokenManager,
                                        OptionChainService optionChainService,
                                        ZerodhaSpotPriceFetcher spotFetcher) {
        this.settingsRepo = settingsRepo;
        this.tradeRepo = tradeRepo;
        this.zerodhaAdapter = zerodhaAdapter;
        this.tokenManager = tokenManager;
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public boolean isAutoExecEnabled() {
        return settingsRepo.findBySettingKey("auto_execute_enabled")
            .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
            .orElse(false);
    }

    public double getMinEdge() {
        return settingsRepo.findBySettingKey("min_edge_after_costs")
            .map(s -> { try { return Double.parseDouble(s.getSettingValue()); } catch (Exception e) { return 500.0; } })
            .orElse(500.0);
    }

    public int getMaxPositionsPerUnderlying() {
        return settingsRepo.findBySettingKey("max_positions_per_underlying")
            .map(s -> { try { return Integer.parseInt(s.getSettingValue()); } catch (Exception e) { return 3; } })
            .orElse(3);
    }

    public int getMaxTotalPositions() {
        return settingsRepo.findBySettingKey("max_total_positions")
            .map(s -> { try { return Integer.parseInt(s.getSettingValue()); } catch (Exception e) { return 12; } })
            .orElse(12);
    }

    public boolean isTimeFilterEnabled() {
        return settingsRepo.findBySettingKey("time_filter_enabled")
            .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
            .orElse(true);
    }

    private boolean isPeakWindow() {
        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean morning = nowIST.isAfter(LocalTime.of(9, 15)) && nowIST.isBefore(LocalTime.of(9, 45));
        boolean afternoon = nowIST.isAfter(LocalTime.of(14, 0)) && nowIST.isBefore(LocalTime.of(15, 0));
        return morning || afternoon;
    }

    private double computeMidPrice(double bid, double ask) {
        if (bid <= 0 || ask <= 0) return (bid + ask) / 2;
        return (bid + ask) / 2.0;
    }

    private boolean isAtmPosition(int strike, double spotPrice) {
        if (spotPrice <= 0) return false;
        double diffPct = Math.abs(strike - spotPrice) / spotPrice * 100.0;
        return diffPct < 2.0;
    }

    public boolean isSmartRollover() {
        return settingsRepo.findBySettingKey("smart_rollover")
            .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
            .orElse(true);
    }

    public double getRollThresholdPct() {
        return settingsRepo.findBySettingKey("roll_threshold_pct")
            .map(s -> { try { return Double.parseDouble(s.getSettingValue()); } catch (Exception e) { return 5.0; } })
            .orElse(5.0);
    }

    public List<String> getTargetUnderlyings() {
        String val = settingsRepo.findBySettingKey("target_underlying")
            .map(AutoExecSetting::getSettingValue)
            .orElse("ALL");
        if ("ALL".equalsIgnoreCase(val) || val.isBlank()) {
            return List.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY");
        }
        return Arrays.stream(val.split(","))
            .map(String::trim)
            .map(String::toUpperCase)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    public void setSetting(String key, String value) {
        AutoExecSetting setting = settingsRepo.findBySettingKey(key).orElse(new AutoExecSetting());
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        settingsRepo.save(setting);
    }

    public Map<String, String> getAllSettings() {
        Map<String, String> map = new LinkedHashMap<>();
        settingsRepo.findAllByOrderBySettingKey().forEach(s -> map.put(s.getSettingKey(), s.getSettingValue()));
        return map;
    }

    public boolean isAutoRolloverEnabled() {
        return settingsRepo.findBySettingKey("auto_rollover_enabled")
            .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
            .orElse(true);
    }

    @Scheduled(fixedDelayString = "${option-arb.auto-exec-interval:30000}", initialDelay = 10000)
    public void autoExecCycle() {
        if (!isAutoExecEnabled()) return;

        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 16)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        if (isTimeFilterEnabled() && !isPeakWindow()) {
            log.debug("Outside peak window — skipping auto-exec");
            return;
        }

        try {
            List<String> underlyings = getTargetUnderlyings();
            double minEdge = getMinEdge();
            int maxTotal = getMaxTotalPositions();
            boolean rolloverEnabled = isAutoRolloverEnabled();

            List<ArbitrageOpportunity> allParityOpps = new ArrayList<>();

            for (String underlying : underlyings) {
                try {
                    double[] spotFut = getSpotAndFutures(underlying);
                    double spot = spotFut[0];
                    double fut = spotFut[1];
                    if (spot <= 0) continue;

                    List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(underlying, spot, fut);
                    opps.stream()
                        .filter(o -> "PARITY_BREAK".equals(o.type))
                        .filter(o -> o.edgeAfterCosts >= minEdge)
                        .sorted((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts))
                        .forEach(allParityOpps::add);
                } catch (Exception e) {
                    log.error("Scan failed for {}: {}", underlying, e.getMessage());
                }
            }

            allParityOpps.sort((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts));

            int totalOpen = tradeRepo.countOpen();

            for (ArbitrageOpportunity opp : allParityOpps) {
                totalOpen = tradeRepo.countOpen();

                if (totalOpen >= maxTotal) {
                    if (rolloverEnabled) {
                        tryRotateWeakest(opp);
                    }
                    break;
                }

                String cooldownKey = opp.underlying + "_" + (int) opp.strike + "_" + opp.action;
                Long lastExec = lastExecTime.get(cooldownKey);
                if (lastExec != null && System.currentTimeMillis() - lastExec < EXEC_COOLDOWN_MS) continue;

                ExecutedTrade existing = findExistingPosition(opp.underlying, (int) opp.strike, opp.action);
                if (existing != null) {
                    log.debug("Already have position at {} {} {}, skipping", opp.underlying, (int) opp.strike, opp.action);
                    continue;
                }

                String note = String.format("Auto-entered: %s %d %s edge=₹%.0f", opp.underlying, (int) opp.strike, opp.action, opp.edgeAfterCosts);
                ExecutedTrade trade = executeNew(opp, note);
                if (trade != null && "OPEN".equals(trade.getStatus())) {
                    log.info("NEW #{}: {} {} {} edge=₹{}", trade.getId(), opp.underlying, (int) opp.strike, opp.action, String.format("%.0f", opp.edgeAfterCosts));
                }
                lastExecTime.put(cooldownKey, System.currentTimeMillis());
                break;
            }
        } catch (Exception e) {
            log.error("Auto-execute cycle failed: {}", e.getMessage(), e);
        }
    }

    private void tryRotateWeakest(ArbitrageOpportunity newOpp) {
        List<ExecutedTrade> openTrades = tradeRepo.findAllOpen();
        if (openTrades.isEmpty()) return;

        ExecutedTrade maxProfitTrade = null;
        double maxPnl = Double.NEGATIVE_INFINITY;

        ExecutedTrade lowestEdgeTrade = null;
        double lowestEdge = Double.MAX_VALUE;

        for (ExecutedTrade trade : openTrades) {
            double[] spotFut = getSpotAndFutures(trade.getUnderlying());
            double spot = spotFut[0];
            double fut = spotFut[1];
            if (spot <= 0) continue;

            double runningPnl = computeRunningPnl(trade, spot, fut);
            double entryEdge = trade.getEdgeAtEntry() != null ? trade.getEdgeAtEntry() : 0;

            log.debug("ROTATION: {} strike={} pnl=₹{} entryEdge=₹{}", trade.getUnderlying(), trade.getStrike(),
                String.format("%.0f", runningPnl), String.format("%.0f", entryEdge));

            if (runningPnl > maxPnl) {
                maxPnl = runningPnl;
                maxProfitTrade = trade;
            }
            if (entryEdge < lowestEdge) {
                lowestEdge = entryEdge;
                lowestEdgeTrade = trade;
            }
        }

        ExecutedTrade target = null;
        String reason = "";

        if (maxProfitTrade != null && maxPnl > 0) {
            target = maxProfitTrade;
            reason = String.format("max-profit ₹%.0f", maxPnl);
        } else if (lowestEdgeTrade != null && lowestEdge < newOpp.edgeAfterCosts) {
            target = lowestEdgeTrade;
            reason = String.format("lowest-edge ₹%.0f < new ₹%.0f", lowestEdge, newOpp.edgeAfterCosts);
        }

        if (target == null) return;

        boolean sameUnderlying = target.getUnderlying().equals(newOpp.underlying);

        if (sameUnderlying && isSmartRollover()) {
            String rollNote = String.format("ROLLOVER: closed %s %d (pnl=₹%.0f, %s) → new %s %d edge=₹%.0f [options-only, same future]",
                target.getUnderlying(), target.getStrike(), maxPnl, reason,
                newOpp.underlying, (int) newOpp.strike, newOpp.edgeAfterCosts);
            log.info("ROTATE (options-only): {}", rollNote);
            rollOptionsOnly(target, newOpp);
            ExecutedTrade newTrade = tradeRepo.findAllOpen().stream()
                .filter(t -> t.getUnderlying().equals(newOpp.underlying) && t.getStrike() == (int) newOpp.strike)
                .max(Comparator.comparing(ExecutedTrade::getId))
                .orElse(null);
            if (newTrade != null) {
                newTrade.setNotes(rollNote);
                tradeRepo.save(newTrade);
            }
        } else {
            String rollNote = String.format("ROLLOVER: closed %s %d (pnl=₹%.0f, %s) → new %s %d edge=₹%.0f [full close+re-enter]",
                target.getUnderlying(), target.getStrike(), maxPnl, reason,
                newOpp.underlying, (int) newOpp.strike, newOpp.edgeAfterCosts);
            log.info("ROTATE (full): {}", rollNote);
            closePositionInternal(target);
            ExecutedTrade newTrade = executeNew(newOpp, rollNote);
            if (newTrade != null && "OPEN".equals(newTrade.getStatus())) {
                log.info("ROTATED #{}→#{}: {}", target.getId(), newTrade.getId(), rollNote);
            }
        }
    }

    @Scheduled(fixedDelayString = "${option-arb.roll-interval:300000}", initialDelay = 60000)
    public void monitorAndRollPositions() {
        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 16)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        List<ExecutedTrade> openTrades = tradeRepo.findAllOpen();
        if (openTrades.isEmpty()) return;

        for (ExecutedTrade trade : openTrades) {
            try {
                String underlying = trade.getUnderlying();
                double[] spotFut = getSpotAndFutures(underlying);
                double currentSpot = spotFut[0];
                double currentFut = spotFut[1];
                if (currentSpot <= 0) continue;

                double runningPnl = computeRunningPnl(trade, currentSpot, currentFut);
                Double edgeAtEntry = trade.getEdgeAtEntry();
                if (edgeAtEntry == null) edgeAtEntry = 0.0;

                log.info("ROLL MONITOR: {} strike={} runningPnl={} edgeAtEntry={}",
                    underlying, trade.getStrike(), String.format("%.0f", runningPnl), String.format("%.0f", edgeAtEntry));

                if (runningPnl >= edgeAtEntry && edgeAtEntry > 0) {
                    log.info("EDGE CAPTURED: {} {} runningPnl={} >= edgeAtEntry={} — scanning for better opps",
                        underlying, trade.getStrike(), String.format("%.0f", runningPnl), String.format("%.0f", edgeAtEntry));

                    List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(underlying, currentSpot, currentFut);
                    Optional<ArbitrageOpportunity> betterOpp = opps.stream()
                        .filter(o -> "PARITY_BREAK".equals(o.type))
                        .filter(o -> o.edgeAfterCosts >= getMinEdge())
                        .filter(o -> (int) o.strike != trade.getStrike())
                        .max(Comparator.comparingDouble(o -> o.edgeAfterCosts));

                    if (betterOpp.isPresent()) {
                        ArbitrageOpportunity newOpp = betterOpp.get();
                        log.info("SMART ROLL: {} {} edge={} → {} edge={} (same future, options only)",
                            underlying, trade.getStrike(), String.format("%.0f", edgeAtEntry), (int) newOpp.strike, String.format("%.0f", newOpp.edgeAfterCosts));
                        rollOptionsOnly(trade, newOpp);
                    } else {
                        log.info("No better opp found for {} — holding position", underlying);
                    }
                } else {
                    double deviationPct = Math.abs(currentSpot - trade.getStrike()) / trade.getStrike() * 100.0;
                    if (deviationPct >= getRollThresholdPct()) {
                        log.info("SPOT MOVED: {} strike={} spot={} deviation={}% — closing all and re-entering",
                            underlying, trade.getStrike(), String.format("%.0f", currentSpot), String.format("%.1f", deviationPct));
                        closePositionInternal(trade);
                        trade.setStatus("ROLLED_MOVE");
                        trade.setClosedAt(LocalDateTime.now());
                        trade.setNotes(String.format("Auto-rolled: spot %.0f moved %.1f%% from strike %d",
                            currentSpot, deviationPct, trade.getStrike()));
                        tradeRepo.save(trade);

                        List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(underlying, currentSpot, currentFut);
                        opps.stream()
                            .filter(o -> "PARITY_BREAK".equals(o.type))
                            .filter(o -> o.edgeAfterCosts >= getMinEdge())
                            .max(Comparator.comparingDouble(o -> o.edgeAfterCosts))
                            .ifPresent(opp -> {
                                log.info("RE-ENTER: {} {} {} edge={}", underlying, (int) opp.strike, opp.action, String.format("%.0f", opp.edgeAfterCosts));
                                executeNew(opp);
                            });
                    }
                }
            } catch (Exception e) {
                log.error("Roll monitor error for {}: {}", trade.getUnderlying(), e.getMessage());
            }
        }
    }

    private double computeRunningPnl(ExecutedTrade trade, double currentSpot, double currentFut) {
        int lotSize = trade.getLotSize();
        String action = trade.getAction();

        double currentCe = 0;
        double currentPe = 0;

        try {
            String ceKey = trade.getCeSymbol();
            String peKey = trade.getPeSymbol();
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceKey, peKey));
            OptionChainService.OptionQuote ceQ = quotes.get(ceKey);
            OptionChainService.OptionQuote peQ = quotes.get(peKey);
            if (ceQ != null && ceQ.lastPrice > 0) currentCe = ceQ.lastPrice;
            if (peQ != null && peQ.lastPrice > 0) currentPe = peQ.lastPrice;
        } catch (Exception e) {
            log.debug("Could not fetch live option prices for {}: {}", trade.getUnderlying(), e.getMessage());
        }

        if (currentCe <= 0 || currentPe <= 0) return 0;

        double cePnl = currentCe - trade.getCeEntryPrice();
        double pePnl = trade.getPeEntryPrice() - currentPe;
        double futPnl = trade.getFutEntryPrice() - currentFut;

        if ("CONVERSION".equals(action)) {
            return (cePnl + pePnl + futPnl) * lotSize;
        } else {
            return (-cePnl - pePnl - futPnl) * lotSize;
        }
    }

    private ExecutedTrade findExistingPosition(String underlying, int strike, String action) {
        List<ExecutedTrade> open = tradeRepo.findOpenByUnderlying(underlying);
        return open.stream()
            .filter(t -> t.getStrike() == strike && t.getAction().equals(action))
            .findFirst()
            .orElse(null);
    }

    public ExecutedTrade executeNew(ArbitrageOpportunity opp) {
        return executeNew(opp, null);
    }

    public ExecutedTrade executeNew(ArbitrageOpportunity opp, String note) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            log.warn("No auth token, cannot execute");
            return null;
        }

        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            log.warn("Market closed, cannot execute");
            return null;
        }

        String token = auth.getAccessToken();
        BigDecimal availableMargin = zerodhaAdapter.getAvailableMargin(token);
        int lotSize = OptionChainService.getLotSize(opp.underlying);
        double ceMid = computeMidPrice(opp.ceBid, opp.ceAsk);
        double peMid = computeMidPrice(opp.peBid, opp.peAsk);
        double hedgedMargin = HEDGED_MARGIN.getOrDefault(opp.underlying, 200000.0) * MIN_MARGIN_BUFFER;
        double estimatedRequired = hedgedMargin;
        if (availableMargin.doubleValue() < estimatedRequired) {
            log.warn("Insufficient margin for {} {} {}: available=₹{} required=₹{}",
                opp.underlying, (int) opp.strike, opp.action, availableMargin.doubleValue(), estimatedRequired);
            return null;
        }

        log.info("EXECUTING: {} {} {} edge=₹{} mid CE={} PE={} (bid/ask CE={}/{} PE={}/{})",
            opp.underlying, (int) opp.strike, opp.action, opp.edgeAfterCosts,
            String.format("%.1f", ceMid), String.format("%.1f", peMid),
            String.format("%.1f", opp.ceBid), String.format("%.1f", opp.ceAsk),
            String.format("%.1f", opp.peBid), String.format("%.1f", opp.peAsk));

        LocalDate expiry = getExpiryDate(opp.underlying);
        String ceSymbol = buildNfoSymbol(opp.underlying, expiry, (int) opp.strike, "CE");
        String peSymbol = buildNfoSymbol(opp.underlying, expiry, (int) opp.strike, "PE");
        String futSymbol = buildNfoFutSymbol(opp.underlying, expiry);

        List<BrokerOrderRequest> orders;
        if ("CONVERSION".equals(opp.action)) {
            orders = List.of(
                new BrokerOrderRequest(ceSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, ceMid, null, "NRML"),
                new BrokerOrderRequest(peSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, peMid, null, "NRML"),
                new BrokerOrderRequest(futSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, opp.futuresPrice, null, "NRML")
            );
        } else {
            orders = List.of(
                new BrokerOrderRequest(ceSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, ceMid, null, "NRML"),
                new BrokerOrderRequest(peSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, peMid, null, "NRML"),
                new BrokerOrderRequest(futSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, opp.futuresPrice, null, "NRML")
            );
        }

        ExecutedTrade trade = new ExecutedTrade();
        trade.setOpportunityId(null);
        trade.setUnderlying(opp.underlying);
        trade.setStrike((int) opp.strike);
        trade.setExpiryDate(expiry);
        trade.setAction(opp.action);
        trade.setCeSymbol(ceSymbol);
        trade.setPeSymbol(peSymbol);
        trade.setFutSymbol(futSymbol);
        trade.setLotSize(lotSize);
        trade.setEdgeAtEntry(opp.edgeAfterCosts);
        trade.setStatus("PENDING");
        if (note != null) trade.setNotes(note);

        List<String> placedOrderIds = new ArrayList<>();
        Map<String, String> orderSideMap = new LinkedHashMap<>();

        for (BrokerOrderRequest order : orders) {
            try {
                BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, order);
                if (resp.orderId() != null) {
                    placedOrderIds.add(resp.orderId());
                    orderSideMap.put(resp.orderId(), order.side().name());
                    if (order.symbol().equals(ceSymbol)) { trade.setCeOrderId(resp.orderId()); trade.setCeEntryPrice(order.price()); }
                    else if (order.symbol().equals(peSymbol)) { trade.setPeOrderId(resp.orderId()); trade.setPeEntryPrice(order.price()); }
                    else if (order.symbol().equals(futSymbol)) { trade.setFutOrderId(resp.orderId()); trade.setFutEntryPrice(order.price()); }
                } else {
                    log.warn("Auto-exec order rejected: {} {} — {}", order.side(), order.symbol(), resp.message());
                }
            } catch (Exception e) {
                log.error("Auto-exec order failed: {} {} — {}", order.side(), order.symbol(), e.getMessage());
            }
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }

        if (placedOrderIds.isEmpty()) {
            trade.setStatus("FAILED");
            trade.setNotes("All orders rejected");
            return tradeRepo.save(trade);
        }

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        Set<String> filledOrderIds = new HashSet<>();
        Set<String> unfilledOrderIds = new HashSet<>();

        for (String orderId : placedOrderIds) {
            try {
                Map<String, Object> details = zerodhaAdapter.getOrderDetails(token, orderId);
                String status = (String) details.getOrDefault("status", "UNKNOWN");
                double avgPrice = (double) details.getOrDefault("average_price", 0.0);
                int filled = (int) details.getOrDefault("filled_quantity", 0);

                if ("COMPLETE".equalsIgnoreCase(status)) {
                    filledOrderIds.add(orderId);
                    String symbol = (String) details.getOrDefault("tradingsymbol", "");
                    if (symbol.endsWith("CE")) trade.setCeEntryPrice(avgPrice);
                    else if (symbol.endsWith("PE")) trade.setPeEntryPrice(avgPrice);
                    else if (symbol.endsWith("FUT")) trade.setFutEntryPrice(avgPrice);
                } else {
                    unfilledOrderIds.add(orderId);
                }
            } catch (Exception e) {
                log.warn("Could not verify fill for {}: {}", orderId, e.getMessage());
                unfilledOrderIds.add(orderId);
            }
        }

        if (filledOrderIds.size() == 3) {
            trade.setStatus("OPEN");
            trade.setNotes("Auto-executed — all 3 legs filled");
        } else if (filledOrderIds.isEmpty()) {
            trade.setStatus("FAILED");
            trade.setNotes("All orders rejected/failed");
        } else {
            log.warn("PARTIAL FILL: {}/3 for {} {} {}. Squaring off filled legs.",
                filledOrderIds.size(), opp.action, opp.underlying, (int) opp.strike);
            squareOffFilledLegs(token, trade, filledOrderIds, orderSideMap, lotSize);
            trade.setStatus("FAILED");
            trade.setNotes(String.format("Partial fill %d/3 — filled legs squared off immediately", filledOrderIds.size()));
        }

        return tradeRepo.save(trade);
    }

    private void squareOffFilledLegs(String token, ExecutedTrade trade, Set<String> filledOrderIds,
                                       Map<String, String> orderSideMap, int lotSize) {
        if (filledOrderIds.isEmpty()) return;
        log.warn("SQUARE-OFF: Closing {} filled legs for trade on {} strike={}",
            filledOrderIds.size(), trade.getUnderlying(), trade.getStrike());

        String[] orderIdArr = filledOrderIds.toArray(new String[0]);
        for (String orderId : orderIdArr) {
            String side = orderSideMap.getOrDefault(orderId, "");
            String symbol = getSymbolForOrder(trade, orderId);
            if (symbol == null || side.isEmpty()) continue;

            BrokerOrderRequest.Side closeSide = "BUY".equals(side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;

            try {
                BrokerOrderRequest closeOrder = new BrokerOrderRequest(symbol, "NFO", closeSide, lotSize, 0.0, null, "NRML");
                BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeOrder);
                log.info("SQUARE-OFF: {} {} {} status={}", closeSide, symbol, lotSize, resp.status());
            } catch (Exception e) {
                log.error("SQUARE-OFF exception for {}: {}", symbol, e.getMessage());
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    private String getSymbolForOrder(ExecutedTrade trade, String orderId) {
        if (orderId.equals(trade.getCeOrderId())) return trade.getCeSymbol();
        if (orderId.equals(trade.getPeOrderId())) return trade.getPeSymbol();
        if (orderId.equals(trade.getFutOrderId())) return trade.getFutSymbol();
        return null;
    }

    public ExecutedTrade rollOptionsOnly(ExecutedTrade existing, ArbitrageOpportunity newOpp) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) return null;

        String token = auth.getAccessToken();
        int lotSize = existing.getLotSize();

        BigDecimal availableMargin = zerodhaAdapter.getAvailableMargin(token);
        double estimatedRequired = (newOpp.cePrice + newOpp.pePrice) * lotSize * MIN_MARGIN_BUFFER;
        if (availableMargin.doubleValue() < estimatedRequired) {
            log.warn("Insufficient margin for rollover: available=₹{},.0f required=₹{},.0f",
                availableMargin.doubleValue(), estimatedRequired);
            return null;
        }

        List<String> closeOrderIds = new ArrayList<>();

        try {
            BrokerOrderRequest closeCE = new BrokerOrderRequest(existing.getCeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeCE);
            if (resp.isSuccess()) closeOrderIds.add(resp.orderId());
        } catch (Exception e) { log.error("Failed to close CE: {}", e.getMessage()); }

        try {
            BrokerOrderRequest closePE = new BrokerOrderRequest(existing.getPeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closePE);
            if (resp.isSuccess()) closeOrderIds.add(resp.orderId());
        } catch (Exception e) { log.error("Failed to close PE: {}", e.getMessage()); }

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        existing.setStatus("ROLLED");
        existing.setClosedAt(LocalDateTime.now());
        existing.setNotes("Rolled options — same futures direction maintained");
        tradeRepo.save(existing);

        LocalDate expiry = getExpiryDate(existing.getUnderlying());
        String newCeSymbol = buildNfoSymbol(existing.getUnderlying(), expiry, (int) newOpp.strike, "CE");
        String newPeSymbol = buildNfoSymbol(existing.getUnderlying(), expiry, (int) newOpp.strike, "PE");
        String newFutSymbol = buildNfoFutSymbol(existing.getUnderlying(), expiry);

        ExecutedTrade newTrade = new ExecutedTrade();
        newTrade.setUnderlying(existing.getUnderlying());
        newTrade.setStrike((int) newOpp.strike);
        newTrade.setExpiryDate(expiry);
        newTrade.setAction(existing.getAction());
        newTrade.setCeSymbol(newCeSymbol);
        newTrade.setPeSymbol(newPeSymbol);
        newTrade.setFutSymbol(newFutSymbol);
        double newCeMid = computeMidPrice(newOpp.ceBid, newOpp.ceAsk);
        double newPeMid = computeMidPrice(newOpp.peBid, newOpp.peAsk);
        newTrade.setCeEntryPrice(newCeMid);
        newTrade.setPeEntryPrice(newPeMid);
        newTrade.setFutEntryPrice(existing.getFutEntryPrice());
        newTrade.setLotSize(lotSize);
        newTrade.setEdgeAtEntry(newOpp.edgeAfterCosts);
        newTrade.setRolloverFromId(existing.getId());
        newTrade.setStatus("PENDING");

        boolean allFilled = true;

        try {
            BrokerOrderRequest openCE = new BrokerOrderRequest(newCeSymbol, "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, newCeMid, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, openCE);
            if (resp.isSuccess()) newTrade.setCeOrderId(resp.orderId());
            else allFilled = false;
        } catch (Exception e) { log.error("Failed to open new CE: {}", e.getMessage()); allFilled = false; }

        try {
            BrokerOrderRequest openPE = new BrokerOrderRequest(newPeSymbol, "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY,
                lotSize, newPeMid, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, openPE);
            if (resp.isSuccess()) newTrade.setPeOrderId(resp.orderId());
            else allFilled = false;
        } catch (Exception e) { log.error("Failed to open new PE: {}", e.getMessage()); allFilled = false; }

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        Set<String> filledIds = new HashSet<>();
        Set<String> unfilledIds = new HashSet<>();
        Map<String, String> sideMap = new LinkedHashMap<>();

        if (newTrade.getCeOrderId() != null) sideMap.put(newTrade.getCeOrderId(), "CONVERSION".equals(existing.getAction()) ? "BUY" : "SELL");
        if (newTrade.getPeOrderId() != null) sideMap.put(newTrade.getPeOrderId(), "CONVERSION".equals(existing.getAction()) ? "SELL" : "BUY");

        for (String oid : sideMap.keySet()) {
            try {
                Map<String, Object> details = zerodhaAdapter.getOrderDetails(token, oid);
                String status = (String) details.getOrDefault("status", "UNKNOWN");
                if ("COMPLETE".equalsIgnoreCase(status)) filledIds.add(oid);
                else unfilledIds.add(oid);
            } catch (Exception e) { unfilledIds.add(oid); }
        }

        if (filledIds.size() == 2) {
            newTrade.setStatus("OPEN");
            newTrade.setNotes("Rolled from trade #" + existing.getId() + " — options only");
        } else if (filledIds.isEmpty()) {
            newTrade.setStatus("FAILED");
            newTrade.setNotes("Rolled but new options failed to fill");
        } else {
            log.warn("PARTIAL ROLL FILL: closing filled new legs");
            for (String oid : filledIds) {
                String symbol = null;
                String side = sideMap.get(oid);
                if (oid.equals(newTrade.getCeOrderId())) symbol = newCeSymbol;
                if (oid.equals(newTrade.getPeOrderId())) symbol = newPeSymbol;
                if (symbol != null) {
                    BrokerOrderRequest.Side closeSide = "BUY".equals(side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
                    try {
                        zerodhaAdapter.placeOrder(token, new BrokerOrderRequest(symbol, "NFO", closeSide, lotSize, 0.0, null, "NRML"));
                    } catch (Exception e) { log.error("Roll square-off failed: {}", e.getMessage()); }
                }
            }
            newTrade.setStatus("FAILED");
            newTrade.setNotes("Partial roll — filled legs squared off");
        }

        tradeRepo.save(newTrade);
        log.info("Smart rollover complete: trade #{} → #{} status={}", existing.getId(), newTrade.getId(), newTrade.getStatus());
        return newTrade;
    }

    private void closeAndReplace(ExecutedTrade existing, ArbitrageOpportunity newOpp) {
        ExecutedTrade closed = closePositionInternal(existing);
        if (closed != null && "CLOSED".equals(closed.getStatus())) {
            executeNew(newOpp);
        }
    }

    public ExecutedTrade closePosition(ExecutedTrade existing) {
        return closePositionInternal(existing);
    }

    @Scheduled(cron = "0 20 15 * * 1-5", zone = "Asia/Kolkata")
    public void autoExitNearExpiry() {
        List<ExecutedTrade> openTrades = tradeRepo.findAllOpen();
        if (openTrades.isEmpty()) return;

        log.info("Near-expiry check: {} open positions", openTrades.size());

        for (ExecutedTrade trade : openTrades) {
            LocalDate expiry = trade.getExpiryDate();
            long dte = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiry);

            if (dte > 1) continue;

            double[] spotFut = getSpotAndFutures(trade.getUnderlying());
            double spot = spotFut[0];
            boolean atm = isAtmPosition(trade.getStrike(), spot);

            if (atm && dte == 1) {
                log.info("HOLD-TO-EXPIRY: {} {} strike={} DTE={} ATM — holding to expiry day",
                    trade.getUnderlying(), trade.getAction(), trade.getStrike(), dte);
                continue;
            }

            log.info("EXIT EXPIRY: {} {} strike={} DTE={} ATM={} — closing",
                trade.getUnderlying(), trade.getAction(), trade.getStrike(), dte, atm);
            closePositionInternal(trade);
        }
    }

    @Scheduled(cron = "0 28 15 * * 1-5", zone = "Asia/Kolkata")
    public void autoExitEndOfDay() {
        List<ExecutedTrade> openTrades = tradeRepo.findAllOpen();
        if (openTrades.isEmpty()) return;

        int expiryDayCount = 0;
        for (ExecutedTrade trade : openTrades) {
            long dte = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), trade.getExpiryDate());
            if (dte <= 0) expiryDayCount++;
        }

        if (expiryDayCount == 0) return;

        log.info("End-of-day expiry cleanup: {} positions expiring today — closing ALL", expiryDayCount);
        for (ExecutedTrade trade : openTrades) {
            long dte = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), trade.getExpiryDate());
            if (dte <= 0) {
                log.info("EOD EXIT: {} {} strike={} — expiry day", trade.getUnderlying(), trade.getAction(), trade.getStrike());
                closePositionInternal(trade);
            }
        }
    }

    private ExecutedTrade closePositionInternal(ExecutedTrade existing) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            log.warn("No auth token for closing position");
            return null;
        }

        int lotSize = existing.getLotSize();
        String token = auth.getAccessToken();
        List<String> closeOrderIds = new ArrayList<>();

        try {
            BrokerOrderRequest closeCE = new BrokerOrderRequest(existing.getCeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeCE);
            if (resp.isSuccess()) { existing.setCloseCeOrderId(resp.orderId()); closeOrderIds.add(resp.orderId()); }
        } catch (Exception e) { log.error("Close CE failed: {}", e.getMessage()); }

        try {
            BrokerOrderRequest closePE = new BrokerOrderRequest(existing.getPeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closePE);
            if (resp.isSuccess()) { existing.setClosePeOrderId(resp.orderId()); closeOrderIds.add(resp.orderId()); }
        } catch (Exception e) { log.error("Close PE failed: {}", e.getMessage()); }

        try {
            BrokerOrderRequest closeFut = new BrokerOrderRequest(existing.getFutSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeFut);
            if (resp.isSuccess()) { existing.setCloseFutOrderId(resp.orderId()); closeOrderIds.add(resp.orderId()); }
        } catch (Exception e) { log.error("Close FUT failed: {}", e.getMessage()); }

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        int closedCount = 0;
        for (String oid : closeOrderIds) {
            try {
                Map<String, Object> details = zerodhaAdapter.getOrderDetails(token, oid);
                String status = (String) details.getOrDefault("status", "UNKNOWN");
                if ("COMPLETE".equalsIgnoreCase(status)) closedCount++;
            } catch (Exception e) {
                log.warn("Could not verify close order {}: {}", oid, e.getMessage());
            }
        }

        if (closedCount == closeOrderIds.size() && !closeOrderIds.isEmpty()) {
            existing.setStatus("CLOSED");
            existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | All legs closed" : "All legs closed");
        } else if (closedCount > 0) {
            existing.setStatus("PARTIALLY_CLOSED");
            existing.setNotes(existing.getNotes() != null
                ? existing.getNotes() + " | Partial close: " + closedCount + "/" + closeOrderIds.size()
                : "Partial close: " + closedCount + "/" + closeOrderIds.size());
        } else {
            existing.setStatus("CLOSE_FAILED");
            existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | Close orders failed" : "Close orders failed");
        }
        existing.setClosedAt(LocalDateTime.now());
        return tradeRepo.save(existing);
    }

    public ExecutedTrade closeOptionsOnly(ExecutedTrade existing) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) return null;

        int lotSize = existing.getLotSize();
        String token = auth.getAccessToken();

        try {
            BrokerOrderRequest closeCE = new BrokerOrderRequest(existing.getCeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeCE);
            if (resp.isSuccess()) existing.setCloseCeOrderId(resp.orderId());
        } catch (Exception e) { log.error("Close CE failed: {}", e.getMessage()); }

        try {
            BrokerOrderRequest closePE = new BrokerOrderRequest(existing.getPeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closePE);
            if (resp.isSuccess()) existing.setClosePeOrderId(resp.orderId());
        } catch (Exception e) { log.error("Close PE failed: {}", e.getMessage()); }

        existing.setStatus("CLOSED_OPTIONS");
        existing.setClosedAt(LocalDateTime.now());
        existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | Options closed, futures kept" : "Options closed, futures kept");
        return tradeRepo.save(existing);
    }

    private void cancelFilledOrders(String token, ExecutedTrade trade) {
        if (trade.getCeOrderId() != null) {
            try { zerodhaAdapter.cancelOrder(token, trade.getCeOrderId()); } catch (Exception e) {}
        }
        if (trade.getPeOrderId() != null) {
            try { zerodhaAdapter.cancelOrder(token, trade.getPeOrderId()); } catch (Exception e) {}
        }
        if (trade.getFutOrderId() != null) {
            try { zerodhaAdapter.cancelOrder(token, trade.getFutOrderId()); } catch (Exception e) {}
        }
    }

    private double[] getSpotAndFutures(String underlying) {
        Map<String, String> spotKeys = Map.of(
            "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
            "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );
        Map<String, String> futPrefixes = Map.of(
            "NIFTY", "NFO:NIFTY", "BANKNIFTY", "NFO:BANKNIFTY",
            "MIDCPNIFTY", "NFO:MIDCPNIFTY", "FINNIFTY", "NFO:FINNIFTY"
        );

        String spotKey = spotKeys.get(underlying);
        String futPrefix = futPrefixes.get(underlying);
        if (spotKey == null || futPrefix == null) return new double[]{0, 0};

        double spot = spotFetcher.getSpotPrice(spotKey);
        if (spot <= 0) return new double[]{0, 0};

        LocalDate expiry = getExpiryDate(underlying);
        int yy = expiry.getYear() % 100;
        String mon = expiry.getMonth().name().substring(0, 3);
        String futKey = String.format("%s%02d%sFUT", futPrefix, yy, mon);

        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        double fut = spotFetcher.getSpotPrice(futKey);
        if (fut <= 0) fut = spot;

        return new double[]{spot, fut};
    }

    private LocalDate getExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        if ("NIFTY".equals(underlying)) {
            LocalDate next = today;
            while (next.getDayOfWeek() != DayOfWeek.TUESDAY) next = next.plusDays(1);
            if (next.equals(today)) {
                LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
                if (nowIST.isAfter(LocalTime.of(15, 0))) next = next.plusWeeks(1);
            }
            return next;
        }
        return getMonthlyExpiryDate();
    }

    private LocalDate getMonthlyExpiryDate() {
        LocalDate today = LocalDate.now();
        LocalDate lastTuesday = today.withDayOfMonth(today.lengthOfMonth());
        while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) lastTuesday = lastTuesday.minusDays(1);
        if (lastTuesday.isBefore(today)) {
            lastTuesday = lastTuesday.plusMonths(1).withDayOfMonth(lastTuesday.plusMonths(1).lengthOfMonth());
            while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) lastTuesday = lastTuesday.minusDays(1);
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
