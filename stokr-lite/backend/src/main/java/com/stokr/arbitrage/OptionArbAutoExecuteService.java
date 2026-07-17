package com.stokr.arbitrage;

import com.stokr.broker.BrokerOrderRequest;
import com.stokr.broker.BrokerOrderResponse;
import com.stokr.broker.ZerodhaAdapter;
import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
            .map(s -> { try { return Integer.parseInt(s.getSettingValue()); } catch (Exception e) { return 2; } })
            .orElse(2);
    }

    public int getMaxTotalPositions() {
        return settingsRepo.findBySettingKey("max_total_positions")
            .map(s -> { try { return Integer.parseInt(s.getSettingValue()); } catch (Exception e) { return 8; } })
            .orElse(8);
    }

    public boolean isSmartRollover() {
        return settingsRepo.findBySettingKey("smart_rollover")
            .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
            .orElse(true);
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

    @Scheduled(fixedDelayString = "${option-arb.auto-exec-interval:300000}", initialDelay = 30000)
    public void autoExecCycle() {
        if (!isAutoExecEnabled()) return;

        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 16)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        log.info("Auto-execute cycle starting...");
        try {
            List<String> underlyings = getTargetUnderlyings();
            double minEdge = getMinEdge();
            int maxPerUnderlying = getMaxPositionsPerUnderlying();
            int maxTotal = getMaxTotalPositions();

            for (String underlying : underlyings) {
                int openCount = tradeRepo.countOpenByUnderlying(underlying);
                if (openCount >= maxPerUnderlying) {
                    log.debug("{} already has {} open positions (max {}), skipping", underlying, openCount, maxPerUnderlying);
                    continue;
                }

                int totalOpen = tradeRepo.countOpen();
                if (totalOpen >= maxTotal) {
                    log.info("Total open positions {} reached max {}, stopping auto-exec", totalOpen, maxTotal);
                    return;
                }

                try {
                    double[] spotFut = getSpotAndFutures(underlying);
                    double spot = spotFut[0];
                    double fut = spotFut[1];
                    if (spot <= 0) continue;

                    List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(underlying, spot, fut);
                    List<ArbitrageOpportunity> parityOpps = opps.stream()
                        .filter(o -> "PARITY_BREAK".equals(o.type))
                        .filter(o -> o.edgeAfterCosts >= minEdge)
                        .sorted((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts))
                        .toList();

                    for (ArbitrageOpportunity opp : parityOpps) {
                        if (openCount >= maxPerUnderlying) break;
                        if (tradeRepo.countOpen() >= maxTotal) return;

                        String cooldownKey = underlying + "_" + (int) opp.strike + "_" + opp.action;
                        Long lastExec = lastExecTime.get(cooldownKey);
                        if (lastExec != null && System.currentTimeMillis() - lastExec < EXEC_COOLDOWN_MS) continue;

                        ExecutedTrade existing = findExistingPosition(underlying, (int) opp.strike, opp.action);
                        if (existing != null) {
                            if (isSmartRollover() && existing.getAction().equals(opp.action)) {
                                log.info("Smart rollover: same futures direction for {} {} {}, rolling options only", underlying, (int) opp.strike, opp.action);
                                rollOptionsOnly(existing, opp);
                            } else {
                                log.info("Closing {} {} {} and replacing with new signal", underlying, (int) opp.strike, existing.getAction());
                                closeAndReplace(existing, opp);
                            }
                        } else {
                            log.info("New position: {} {} {} edge={}", underlying, (int) opp.strike, opp.action, opp.edgeAfterCosts);
                            executeNew(opp);
                        }
                        lastExecTime.put(cooldownKey, System.currentTimeMillis());
                        openCount++;
                    }
                } catch (Exception e) {
                    log.error("Error in auto-exec for {}: {}", underlying, e.getMessage());
                }
            }
            log.info("Auto-execute cycle complete");
        } catch (Exception e) {
            log.error("Auto-execute cycle failed: {}", e.getMessage(), e);
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

        int lotSize = OptionChainService.getLotSize(opp.underlying);
        LocalDate expiry = getExpiryDate(opp.underlying);
        String ceSymbol = buildNfoSymbol(opp.underlying, expiry, (int) opp.strike, "CE");
        String peSymbol = buildNfoSymbol(opp.underlying, expiry, (int) opp.strike, "PE");
        String futSymbol = buildNfoFutSymbol(opp.underlying, expiry);

        List<BrokerOrderRequest> orders;
        if ("CONVERSION".equals(opp.action)) {
            orders = List.of(
                new BrokerOrderRequest(ceSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, opp.cePrice, null, "NRML"),
                new BrokerOrderRequest(peSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, opp.pePrice, null, "NRML"),
                new BrokerOrderRequest(futSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, opp.futuresPrice, null, "NRML")
            );
        } else {
            orders = List.of(
                new BrokerOrderRequest(ceSymbol, "NFO", BrokerOrderRequest.Side.SELL, lotSize, opp.cePrice, null, "NRML"),
                new BrokerOrderRequest(peSymbol, "NFO", BrokerOrderRequest.Side.BUY, lotSize, opp.pePrice, null, "NRML"),
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
        trade.setStatus("OPEN");

        boolean allFilled = true;
        for (BrokerOrderRequest order : orders) {
            try {
                BrokerOrderResponse resp = zerodhaAdapter.placeOrder(auth.getAccessToken(), order);
                if (resp.isSuccess()) {
                    if (order.symbol().equals(ceSymbol)) { trade.setCeOrderId(resp.orderId()); trade.setCeEntryPrice(order.price()); }
                    else if (order.symbol().equals(peSymbol)) { trade.setPeOrderId(resp.orderId()); trade.setPeEntryPrice(order.price()); }
                    else if (order.symbol().equals(futSymbol)) { trade.setFutOrderId(resp.orderId()); trade.setFutEntryPrice(order.price()); }
                } else {
                    allFilled = false;
                    log.warn("Auto-exec order rejected: {} {} — {}", order.side(), order.symbol(), resp.message());
                }
            } catch (Exception e) {
                allFilled = false;
                log.error("Auto-exec order failed: {} {} — {}", order.side(), order.symbol(), e.getMessage());
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        if (!allFilled) {
            cancelFilledOrders(auth.getAccessToken(), trade);
            trade.setStatus("FAILED");
            trade.setNotes("Partial fill — cancelled");
        }

        trade.setNotes(trade.getNotes() != null ? trade.getNotes() : "Auto-executed");
        return tradeRepo.save(trade);
    }

    public ExecutedTrade rollOptionsOnly(ExecutedTrade existing, ArbitrageOpportunity newOpp) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) return null;

        int lotSize = existing.getLotSize();
        String token = auth.getAccessToken();

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

        ExecutedTrade newTrade = new ExecutedTrade();
        newTrade.setUnderlying(existing.getUnderlying());
        newTrade.setStrike(existing.getStrike());
        newTrade.setExpiryDate(existing.getExpiryDate());
        newTrade.setAction(existing.getAction());
        newTrade.setCeSymbol(existing.getCeSymbol());
        newTrade.setPeSymbol(existing.getPeSymbol());
        newTrade.setFutSymbol(existing.getFutSymbol());
        newTrade.setCeEntryPrice(newOpp.cePrice);
        newTrade.setPeEntryPrice(newOpp.pePrice);
        newTrade.setFutEntryPrice(existing.getFutEntryPrice());
        newTrade.setLotSize(lotSize);
        newTrade.setRolloverFromId(existing.getId());
        newTrade.setStatus("OPEN");

        try {
            BrokerOrderRequest openCE = new BrokerOrderRequest(existing.getCeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, newOpp.cePrice, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, openCE);
            if (resp.isSuccess()) newTrade.setCeOrderId(resp.orderId());
        } catch (Exception e) { log.error("Failed to open new CE: {}", e.getMessage()); }

        try {
            BrokerOrderRequest openPE = new BrokerOrderRequest(existing.getPeSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY,
                lotSize, newOpp.pePrice, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, openPE);
            if (resp.isSuccess()) newTrade.setPeOrderId(resp.orderId());
        } catch (Exception e) { log.error("Failed to open new PE: {}", e.getMessage()); }

        newTrade.setNotes("Rolled from trade #" + existing.getId() + " — options only");
        tradeRepo.save(newTrade);
        log.info("Smart rollover complete: trade #{} → #{}", existing.getId(), newTrade.getId());
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

    private ExecutedTrade closePositionInternal(ExecutedTrade existing) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            log.warn("No auth token for closing position");
            return null;
        }

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

        try {
            BrokerOrderRequest closeFut = new BrokerOrderRequest(existing.getFutSymbol(), "NFO",
                "CONVERSION".equals(existing.getAction()) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                lotSize, 0.0, null, "NRML");
            BrokerOrderResponse resp = zerodhaAdapter.placeOrder(token, closeFut);
            if (resp.isSuccess()) existing.setCloseFutOrderId(resp.orderId());
        } catch (Exception e) { log.error("Close FUT failed: {}", e.getMessage()); }

        existing.setStatus("CLOSED");
        existing.setClosedAt(LocalDateTime.now());
        existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | Manually closed" : "Manually closed");
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
