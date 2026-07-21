package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Comparator;

import com.stokr.marketdata.tick.BidParityDepthCache;
import com.stokr.marketdata.tick.BidParityDepthCache.DepthTick;
import com.stokr.marketdata.tick.KiteInstrumentTokenCache;
import com.stokr.marketdata.tick.KiteTickWebSocketClient;

@Service
public class BidParityAutoTrader {

    private static final Logger log = LoggerFactory.getLogger(BidParityAutoTrader.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbExecutionService executionService;
    private final ExecutedTradeRepository tradeRepo;
    private final OptionArbAutoExecuteService autoExecService;
    private final BidParityDepthCache depthCache;
    private final KiteTickWebSocketClient wsClient;
    private final KiteInstrumentTokenCache tokenCache;

    private static final double RISK_FREE_RATE = 0.065;
    private static final int STRIKE_RANGE = 5;
    private static final long ENTRY_COOLDOWN_MS = 120_000;
    private static final long MIN_HOLD_SECONDS = 300;

    private final ConcurrentHashMap<String, Long> lastEntryTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> liveTickCache = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    private static final Map<String, String> CONFIGS_SPOT = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    private static final Map<String, String> CONFIGS_FUT = Map.of(
        "NIFTY", "NFO:NIFTY", "BANKNIFTY", "NFO:BANKNIFTY",
        "MIDCPNIFTY", "NFO:MIDCPNIFTY", "FINNIFTY", "NFO:FINNIFTY"
    );

    private static final Map<String, double[]> DTE_RANGES = Map.of(
        "NIFTY", new double[]{0, 7}, "BANKNIFTY", new double[]{0, 21},
        "MIDCPNIFTY", new double[]{0, 21}, "FINNIFTY", new double[]{0, 21}
    );

    public BidParityAutoTrader(OptionChainService optionChainService,
                                ZerodhaSpotPriceFetcher spotFetcher,
                                OptionArbExecutionService executionService,
                                ExecutedTradeRepository tradeRepo,
                                OptionArbAutoExecuteService autoExecService,
                                BidParityDepthCache depthCache,
                                KiteTickWebSocketClient wsClient,
                                KiteInstrumentTokenCache tokenCache) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.executionService = executionService;
        this.tradeRepo = tradeRepo;
        this.autoExecService = autoExecService;
        this.depthCache = depthCache;
        this.wsClient = wsClient;
        this.tokenCache = tokenCache;
    }

    @Scheduled(fixedDelayString = "${bid-parity.poll-interval:5000}", initialDelay = 15000)
    public void tickCycle() {
        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) return;
        if (!isAutoBidParityEnabled() && !isAutoExitEnabled()) return;

        running = true;
        try { scanAndDecide(); } catch (Exception e) { log.error("Bid parity tick error: {}", e.getMessage()); }
        running = false;
    }

    private boolean isAutoBidParityEnabled() {
        return autoExecService.getSettingDouble("bid_parity_auto_enabled", 0) == 1;
    }

    private boolean isAutoExitEnabled() {
        return autoExecService.getSettingDouble("bid_parity_auto_exit", 1) == 1;
    }

    private boolean isAutoRolloverEnabled() {
        return autoExecService.getSettingDouble("bid_parity_auto_rollover", 0) == 1;
    }

    private void scanAndDecide() {
        List<String> underlyings = autoExecService.getTargetUnderlyings();
        List<Map<String, Object>> allOpps = new ArrayList<>();

        for (String u : underlyings) {
            try {
                List<Map<String, Object>> opps = scanSingle(u);
                allOpps.addAll(opps);
                for (Map<String, Object> opp : opps) {
                    liveTickCache.put(u + "_" + opp.get("strike"), opp);
                }
            } catch (Exception e) {
                log.debug("Scan {} failed: {}", u, e.getMessage());
            }
        }

        List<ExecutedTrade> openBidPositions = tradeRepo.findAllOpen().stream()
            .filter(t -> "BID_PARITY".equals(t.getBidType())).toList();

        for (ExecutedTrade pos : openBidPositions) {
            DepthTick dt = depthCache.get(pos.getCeSymbol());
            DepthTick pt = depthCache.get(pos.getPeSymbol());
            if (dt == null || pt == null) continue;

            String ceSymbol = pos.getCeSymbol();
            String peSymbol = pos.getPeSymbol();
            double ceBid = dt.bidPrice;
            double peBid = pt.bidPrice;
            long ceBidQty = dt.bidQty;
            long peBidQty = pt.bidQty;

            String strikeKey = pos.getUnderlying() + "_" + pos.getStrike();
            Map<String, Object> tick = liveTickCache.get(strikeKey);
            String currentAction;
            if (tick != null) {
                currentAction = (String) tick.get("action");
            } else {
                currentAction = pos.getAction();
            }
            String entryAction = pos.getAction();
            boolean oppositeDeviation = !currentAction.equals(entryAction);
            boolean enoughQty = ceBidQty >= pos.getLotSize() && peBidQty >= pos.getLotSize();
            boolean heldLongEnough = pos.getExecutedAt() != null &&
                Duration.between(pos.getExecutedAt(), java.time.LocalDateTime.now()).getSeconds() > MIN_HOLD_SECONDS;

            double runningPnl;
            if ("CONVERSION".equals(entryAction)) {
                runningPnl = (ceBid - pos.getCeEntryPrice()) + (pos.getPeEntryPrice() - peBid);
            } else {
                runningPnl = (pos.getCeEntryPrice() - ceBid) + (peBid - pos.getPeEntryPrice());
            }
            double exitEdge = autoExecService.getSettingDouble("bid_parity_exit_edge", 100);
            boolean edgeDropped = runningPnl * pos.getLotSize() < exitEdge;

            boolean shouldExit = heldLongEnough && enoughQty && isAutoExitEnabled() && (oppositeDeviation || edgeDropped);

            if (shouldExit) {
                try {
                    autoExecService.closePosition(pos);
                    pos.setCeBidPriceExit(ceBid);
                    pos.setPeBidPriceExit(peBid);
                    pos.setCeBidQtyExit(ceBidQty);
                    pos.setPeBidQtyExit(peBidQty);

                    double exitPnl;
                    if ("CONVERSION".equals(entryAction)) {
                        exitPnl = (ceBid - pos.getCeEntryPrice()) + (pos.getPeEntryPrice() - peBid);
                    } else {
                        exitPnl = (pos.getCeEntryPrice() - ceBid) + (peBid - pos.getPeEntryPrice());
                    }
                    pos.setPnlPoints(exitPnl);
                    pos.setPnlAmount(exitPnl * pos.getLotSize());
                    pos.setNotes(pos.getNotes() + " | AUTO EXIT CE@" + ceBid + " PE@" + peBid);
                    tradeRepo.save(pos);
                    log.info("EXITED: {} {} P&L: {} pts / Rs.{}",
                        pos.getUnderlying(), pos.getStrike(),
                        String.format("%.1f", exitPnl),
                        String.format("%.0f", exitPnl * pos.getLotSize()));
                } catch (Exception e) {
                    log.error("Exit failed for {} {}: {}", pos.getUnderlying(), pos.getStrike(), e.getMessage());
                }
            }
        }

        int totalOpen = tradeRepo.countOpen();
        int maxPositions = (int) autoExecService.getSettingDouble("bid_parity_max_sets", 3);

        if (isAutoRolloverEnabled() && !openBidPositions.isEmpty()) {
            for (ExecutedTrade pos : openBidPositions) {
                String posUnderlying = pos.getUnderlying();
                int posStrike = pos.getStrike();
                Map<String, Object> bestNewOpp = allOpps.stream()
                    .filter(o -> posUnderlying.equals(o.get("underlying")))
                    .filter(o -> ((Number) o.get("strike")).intValue() != posStrike)
                    .filter(o -> (double) o.getOrDefault("edgeAfterCosts", 0.0) > 0)
                    .max(Comparator.comparingDouble(o -> (double) o.getOrDefault("edgeAfterCosts", 0.0)))
                    .orElse(null);
                if (bestNewOpp == null) continue;
                double currentEdge = pos.getEdgeAtEntry() != null ? pos.getEdgeAtEntry() : 0;
                double newEdge = (double) bestNewOpp.getOrDefault("edgeAfterCosts", 0.0);
                if (newEdge <= currentEdge * 1.2) continue;
                log.info("ROLLOVER: {} {} edge={} -> better opp {} {} edge={}",
                    posUnderlying, posStrike, String.format("%.0f", currentEdge),
                    posUnderlying, bestNewOpp.get("strike"), String.format("%.0f", newEdge));
                try {
                    autoExecService.closePosition(pos);
                    pos.setNotes(pos.getNotes() + " | AUTO ROLLOVER to " + bestNewOpp.get("strike"));
                    tradeRepo.save(pos);
                    totalOpen--;
                } catch (Exception e) {
                    log.error("Rollover close failed for {} {}: {}", posUnderlying, posStrike, e.getMessage());
                }
            }
        }

        if (!isAutoBidParityEnabled()) return;

        for (Map<String, Object> opp : allOpps) {
            if (totalOpen >= maxPositions) break;

            String underlying = (String) opp.get("underlying");
            int strike = ((Number) opp.get("strike")).intValue();
            String action = (String) opp.get("action");
            double edgeAfterCosts = (double) opp.getOrDefault("edgeAfterCosts", 0.0);
            long ceBidQty = ((Number) opp.getOrDefault("ceBidQty", 0L)).longValue();
            long peBidQty = ((Number) opp.getOrDefault("peBidQty", 0L)).longValue();
            int lotSize = ((Number) opp.getOrDefault("lotSize", 65)).intValue();

            boolean alreadyHave = openBidPositions.stream()
                .anyMatch(t -> t.getUnderlying().equals(underlying) && t.getStrike() == strike);
            if (alreadyHave) continue;

            String cooldownKey = underlying + "_" + strike + "_BID_PARITY";
            Long lastEntry = lastEntryTime.get(cooldownKey);
            if (lastEntry != null && System.currentTimeMillis() - lastEntry < ENTRY_COOLDOWN_MS) continue;

            double minEdge = autoExecService.getSettingDouble("scanner_minEdgeAfterCosts", 300);
            if (ceBidQty < lotSize || peBidQty < lotSize || edgeAfterCosts < minEdge) continue;

            double ceBid = (double) opp.getOrDefault("ceBid", 0.0);
            double ceAsk = (double) opp.getOrDefault("ceAsk", 0.0);
            double peBid = (double) opp.getOrDefault("peBid", 0.0);
            double peAsk = (double) opp.getOrDefault("peAsk", 0.0);
            double futuresPrice = (double) opp.getOrDefault("futuresPrice", 0.0);
            double spotPrice = (double) opp.getOrDefault("spotPrice", 0.0);
            double dev = (double) opp.getOrDefault("bidParityDev", 0.0);

            try {
                double execCePrice, execPePrice;
                if ("CONVERSION".equals(action)) {
                    execCePrice = ceAsk;
                    execPePrice = peBid;
                } else {
                    execCePrice = ceBid;
                    execPePrice = peAsk;
                }
                OptionArbExecutionService.ExecutionResult execResult = executionService.execute(
                    underlying, strike, action, execCePrice, execPePrice, futuresPrice, spotPrice, lotSize);

                if (execResult.isSuccess()) {
                    ExecutedTrade trade = new ExecutedTrade();
                    trade.setUnderlying(underlying);
                    trade.setStrike(strike);
                    trade.setAction(action);
                    trade.setExpiryDate(optionChainService.getMonthlyExpiry());
                    trade.setLotSize(lotSize);
                    trade.setStatus("OPEN");
                    trade.setBidType("BID_PARITY");
                    trade.setEdgeAtEntry(dev);
                    trade.setCeBidPriceEntry(ceBid);
                    trade.setPeBidPriceEntry(peBid);
                    trade.setCeBidQtyEntry(ceBidQty);
                    trade.setPeBidQtyEntry(peBidQty);

                    for (OptionArbExecutionService.LegResult leg : execResult.getLegs()) {
                        double fp = leg.getFillPrice() > 0 ? leg.getFillPrice() : leg.getRequestedPrice();
                        if (leg.getSymbol() != null && leg.getSymbol().endsWith("CE")) {
                            trade.setCeSymbol(leg.getSymbol()); trade.setCeOrderId(leg.getOrderId()); trade.setCeEntryPrice(fp);
                        } else if (leg.getSymbol() != null && leg.getSymbol().endsWith("PE")) {
                            trade.setPeSymbol(leg.getSymbol()); trade.setPeOrderId(leg.getOrderId()); trade.setPeEntryPrice(fp);
                        } else if (leg.getSymbol() != null && leg.getSymbol().endsWith("FUT")) {
                            trade.setFutSymbol(leg.getSymbol()); trade.setFutOrderId(leg.getOrderId()); trade.setFutEntryPrice(fp);
                        }
                    }
                    trade.setNotes("AUTO BID PARITY ENTRY dev " + String.format("%.1f", dev) + " pts");
                    tradeRepo.save(trade);
                    lastEntryTime.put(cooldownKey, System.currentTimeMillis());
                    totalOpen++;
                    log.info("AUTO ENTERED: {} {} {} trade ID {}", underlying, strike, action, trade.getId());
                }
            } catch (Exception e) {
                log.error("Auto entry failed for {} {}: {}", underlying, strike, e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> scanSingle(String underlying) {
        List<Map<String, Object>> opportunities = new ArrayList<>();
        String spotKey = CONFIGS_SPOT.get(underlying);
        String futPrefix = CONFIGS_FUT.get(underlying);
        if (spotKey == null) return opportunities;

        double spot = spotFetcher.getSpotPrice(spotKey);
        if (spot <= 0) return opportunities;
        double futuresPrice = spotFetcher.getSpotPrice(futPrefix);
        if (futuresPrice <= 0) futuresPrice = spot;

        int atmStrike = optionChainService.getATMStrike(underlying, spot);
        int step;
        switch (underlying) {
            case "BANKNIFTY": step = 100; break;
            case "MIDCPNIFTY": case "FINNIFTY": step = 50; break;
            default: step = 50;
        }
        List<Integer> strikes = new ArrayList<>();
        for (int i = -STRIKE_RANGE; i <= STRIKE_RANGE; i++) strikes.add(atmStrike + i * step);

        LocalDate expiryDate = optionChainService.getWeeklyExpiryDate(underlying);
        double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiryDate.atStartOfDay()).toDays();
        double yearsToExpiry = daysToExpiry / 365.0;
        if (daysToExpiry < 0) return opportunities;

        double[] dteRange = DTE_RANGES.getOrDefault(underlying, new double[]{3, 21});
        if (daysToExpiry < dteRange[0] || daysToExpiry > dteRange[1]) return opportunities;

        List<String> instruments = new ArrayList<>();
        for (int strike : strikes) {
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "CE"));
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "PE"));
        }

        Map<String, Integer> tokenMap = tokenCache.getTokens(instruments);
        if (!tokenMap.isEmpty()) {
            wsClient.subscribeBatch(tokenMap);
        }

        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
        int lotSize = OptionChainService.getLotSize(underlying);

        for (int strike : strikes) {
            String ceSym = optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "CE");
            String peSym = optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "PE");
            OptionChainService.OptionQuote ceQ = quotes.get(ceSym);
            OptionChainService.OptionQuote peQ = quotes.get(peSym);
            if (ceQ == null || peQ == null) continue;
            if (ceQ.lastPrice <= 0 || peQ.lastPrice <= 0 || ceQ.bid <= 0 || peQ.bid <= 0) continue;

            double ceBid = ceQ.bid, peBid = peQ.bid;
            double ceAsk = ceQ.ask > 0 ? ceQ.ask : ceQ.lastPrice;
            double peAsk = peQ.ask > 0 ? peQ.ask : peQ.lastPrice;

            double syntheticBid = strike + (ceBid - peBid) * Math.exp(RISK_FREE_RATE * yearsToExpiry);
            double bidParityDev = syntheticBid - futuresPrice;

            double grossEdge;
            if (bidParityDev > 0) {
                // REVERSAL: sell CE @ bid, buy PE @ ask, buy FUT
                grossEdge = ((strike + ceBid - peAsk) - futuresPrice) * lotSize;
            } else {
                // CONVERSION: buy CE @ ask, sell PE @ bid, sell FUT
                grossEdge = (futuresPrice - (strike + ceAsk - peBid)) * lotSize;
            }
            double costs = Math.abs(grossEdge) * 0.001 + 120.0 + Math.abs(grossEdge) * 0.0000345 + Math.abs(grossEdge) * 0.000001
                + (120.0 + Math.abs(grossEdge) * 0.000001) * 0.18 + Math.abs(grossEdge) * 0.0000001;
            double netEdge = grossEdge - costs;

            Map<String, Object> opp = new LinkedHashMap<>();
            opp.put("type", "BID_PARITY");
            opp.put("underlying", underlying);
            opp.put("strike", strike);
            opp.put("action", bidParityDev > 0 ? "REVERSAL" : "CONVERSION");
            opp.put("spotPrice", spot);
            opp.put("futuresPrice", futuresPrice);
            opp.put("daysToExpiry", daysToExpiry);
            opp.put("lotSize", lotSize);
            opp.put("ceSymbol", ceSym);
            opp.put("peSymbol", peSym);
            opp.put("ceBid", ceBid);
            opp.put("ceAsk", ceAsk);
            opp.put("peBid", peBid);
            opp.put("peAsk", peAsk);
            opp.put("ceBidQty", ceQ.bidQty);
            opp.put("peBidQty", peQ.bidQty);
            opp.put("ceLastPrice", ceQ.lastPrice);
            opp.put("peLastPrice", peQ.lastPrice);
            opp.put("syntheticBid", Math.round(syntheticBid * 100.0) / 100.0);
            opp.put("bidParityDev", Math.round(bidParityDev * 100.0) / 100.0);
            opp.put("edgePoints", Math.round(Math.abs(grossEdge / lotSize) * 100.0) / 100.0);
            opp.put("edgeAfterCosts", Math.round(netEdge * 100.0) / 100.0);
            opportunities.add(opp);
        }
        return opportunities;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running);
        status.put("autoEnabled", isAutoBidParityEnabled());
        status.put("autoExitEnabled", isAutoExitEnabled());
        status.put("liveTicks", liveTickCache.size());
        status.put("wsDepthTicks", depthCache.getAll().size());
        status.put("wsSubscriptions", tokenCache.size() + " instruments loaded");
        status.put("liveData", new LinkedHashMap<>(liveTickCache));
        return status;
    }

    public Map<String, Map<String, Object>> getAllLiveTicks() {
        return new LinkedHashMap<>(liveTickCache);
    }
}
