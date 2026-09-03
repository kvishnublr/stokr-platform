package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class BoxSpreadService {
    private boolean isLiquid(OptionChainService.OptionQuote q, int lotSize) {
        if (q == null) return false;
        if (q.volume <= 0) return false;
        if (q.bidQty < lotSize || q.askQty < lotSize) return false;
        return true;
    }


    private static final Logger log = LoggerFactory.getLogger(BoxSpreadService.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private static final double MIN_BOX_EDGE_AFTER_COSTS = 0.0;

    public BoxSpreadService(OptionChainService optionChainService,
                            OptionArbHistoryService historyService,
                            ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanBoxSpread(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying) 
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();

        Map<String, String> spotKeys = Map.of(
            "NIFTY", "NSE:NIFTY 50",
            "BANKNIFTY", "NSE:NIFTY BANK",
            "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
            "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);

                double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
                if (spot <= 0 && fut > 0) spot = fut;

                if (spot <= 0) continue;

                List<ArbitrageOpportunity> opps = scanBoxSpreadForUnderlying(u, spot, fut);
                if (opps != null && !opps.isEmpty()) {
                    var savedEntities = historyService.saveOpportunities(opps, u, "BOX_SPREAD");

                    for (int idx = 0; idx < opps.size(); idx++) {
                        Map<String, Object> map = opps.get(idx).toMap();
                        map.put("strategyType", "BOX_SPREAD");
                        map.put("status", "RUNNING");
                        map.put("guaranteedFill", true);
                        map.put("boxEdgeInr", opps.get(idx).edgeAfterCosts);
                        map.put("scanTime", java.time.LocalDateTime.now().toString());
                        map.put("detectedAt", java.time.LocalDateTime.now().toString());
                        if (idx < savedEntities.size()) {
                            map.put("id", savedEntities.get(idx).getId());
                            map.put("expiryDate", savedEntities.get(idx).getExpiryDate() != null ? savedEntities.get(idx).getExpiryDate().toString() : null);
                        }
                        results.add(map);
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Box Spread for {}: {}", u, e.getMessage(), e);
            }
        }

        return results;
    }

    public List<ArbitrageOpportunity> scanBoxSpreadForUnderlying(String underlying, double spotPrice, double futuresPrice) {
        List<ArbitrageOpportunity> opps = new ArrayList<>();
        try {
            int step = OptionChainService.getStrikeStep(underlying);
            int atmStrike = (int) (Math.round(spotPrice / step) * step);
            int lotSize = OptionChainService.getLotSize(underlying);

            LocalDate weeklyExpiry = optionChainService.getWeeklyExpiryDate(underlying);
            if (weeklyExpiry == null) {
                log.warn("No weekly expiry found for {}", underlying);
                return opps;
            }

            List<Integer> strikes = new ArrayList<>();
            for (int i = -3; i <= 3; i++) {
                strikes.add(atmStrike + i * step);
            }

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.add(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

            for (int i = 0; i < strikes.size(); i++) {
                for (int j = i + 1; j < strikes.size(); j++) {
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(j);
                    double width = k2 - k1;

                    String ce1Key = optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k1, "CE");
                    String pe1Key = optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k1, "PE");
                    String ce2Key = optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k2, "CE");
                    String pe2Key = optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k2, "PE");

                    OptionChainService.OptionQuote ce1 = quotes.get(ce1Key);
                    OptionChainService.OptionQuote pe1 = quotes.get(pe1Key);
                    OptionChainService.OptionQuote ce2 = quotes.get(ce2Key);
                    OptionChainService.OptionQuote pe2 = quotes.get(pe2Key);

                    if (!isLiquid(ce1, lotSize) || !isLiquid(pe1, lotSize) || !isLiquid(ce2, lotSize) || !isLiquid(pe2, lotSize)) continue;

                    // Require real live bid/ask -- no fallback to lastPrice. A missing quote
                    // (empty order book, illiquid strike, or market closed) must be skipped,
                    // not silently priced off a stale last-traded print that isn't actually
                    // tradeable right now. Matches BidParityService's convention.
                    if (ce1.ask <= 0 || pe1.bid <= 0 || ce2.bid <= 0 || pe2.ask <= 0) continue;

                    double ce1Ask = ce1.ask;
                    double pe1Bid = pe1.bid;
                    double ce2Bid = ce2.bid;
                    double pe2Ask = pe2.ask;

                    double buyBoxCost = ce1Ask - pe1Bid - ce2Bid + pe2Ask;
                    double buyBoxEdgePoints = width - buyBoxCost;
                    double grossBuyEdge = buyBoxEdgePoints * lotSize;

                    // Real STT/brokerage/exchange/GST/stamp computed from actual quoted prices
                    // (turnover-based, matching Vertical/Butterfly/Condor) -- NOT a percentage
                    // of the edge itself, which massively understates real costs since the
                    // edge is supposed to be small while the actual premium turnover is not.
                    double turnover = (ce1Ask + pe1Bid + ce2Bid + pe2Ask) * lotSize;
                    double sttBuy = (ce1Ask + pe2Ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
                    double sttSell = (pe1Bid + ce2Bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
                    double stt = sttBuy + sttSell;
                    double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
                    double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
                    double sebi = turnover * ArbitrageCosts.SEBI_RATE;
                    double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
                    double stamp = turnover * ArbitrageCosts.STAMP_RATE;
                    double totalCosts = stt + brokerage + exchange + sebi + gst + stamp;
                    double netBuyEdge = grossBuyEdge - totalCosts;

                    if (k1 == atmStrike || (i == 0 && j == strikes.size() - 1)) {
                        log.info("BOX_DEV {}: K1={} K2={} width={} CE1={}/{} PE1={}/{} CE2={}/{} PE2={}/{} cost={} edgePts={} grossEdge={} netEdge={}",
                            underlying, k1, k2, width, ce1.bid, ce1.ask, pe1.bid, pe1.ask, ce2.bid, ce2.ask, pe2.bid, pe2.ask,
                            buyBoxCost, buyBoxEdgePoints, grossBuyEdge, netBuyEdge);
                    }

                    if (netBuyEdge >= MIN_BOX_EDGE_AFTER_COSTS) {
                        long daysToExpiry = java.time.Duration.between(
                            LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays();

                        ArbitrageOpportunity opp = new ArbitrageOpportunity();
                        opp.underlying = underlying;
                        opp.strike = k1;
                        opp.type = "BOX_SPREAD";
                        opp.action = "LONG BOX (" + k1 + "/" + k2 + ")";
                        opp.legs = String.format("BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d PE @ %.1f",
                            k1, ce1Ask, k1, pe1Bid, k2, ce2Bid, k2, pe2Ask);
                        opp.description = String.format("Width=%d | Cost=%.1f | Net=%.1f", (int)width, buyBoxCost, netBuyEdge);
                        opp.spotPrice = spotPrice;
                        opp.futuresPrice = futuresPrice;
                        opp.cePrice = ce1.lastPrice;
                        opp.pePrice = pe1.lastPrice;
                        opp.ceBid = ce1.bid;
                        opp.ceAsk = ce1.ask;
                        opp.peBid = pe1.bid;
                        opp.peAsk = pe1.ask;
                        opp.edgePoints = Math.round(buyBoxEdgePoints * 100.0) / 100.0;
                        opp.edgeAfterCosts = Math.round(netBuyEdge * 100.0) / 100.0;
                        opp.daysToExpiry = daysToExpiry;
                        opp.confidence = 95.0;

                        Map<String, Double> costs = new LinkedHashMap<>();
                        costs.put("stt", Math.round(stt * 100.0) / 100.0);
                        costs.put("brokerage", brokerage);
                        costs.put("exchange", Math.round(exchange * 100.0) / 100.0);
                        costs.put("sebi", Math.round(sebi * 100.0) / 100.0);
                        costs.put("gst", Math.round(gst * 100.0) / 100.0);
                        costs.put("stamp", Math.round(stamp * 100.0) / 100.0);
                        costs.put("totalCosts", Math.round(totalCosts * 100.0) / 100.0);
                        costs.put("netEdge", Math.round(netBuyEdge * 100.0) / 100.0);
                        opp.costBreakdown = costs;
                        opp.legList = List.of(
                            leg(k1, "CE", "BUY", 1, ce1Ask), leg(k1, "PE", "SELL", 1, pe1Bid),
                            leg(k2, "CE", "SELL", 1, ce2Bid), leg(k2, "PE", "BUY", 1, pe2Ask)
                        );

                        opps.add(opp);
                        continue;
                    }

                    // Short box: sell CE1, buy PE1, buy CE2, sell PE2 -- a box's payoff at
                    // expiry is exactly `width`, always. If you can collect MORE than width
                    // selling it, that's equally a locked-in profit as buying it for less.
                    if (ce1.bid <= 0 || pe1.ask <= 0 || ce2.ask <= 0 || pe2.bid <= 0) continue;
                    double sellBoxCredit = ce1.bid - pe1.ask - ce2.ask + pe2.bid;
                    double sellBoxEdgePoints = sellBoxCredit - width;
                    if (sellBoxEdgePoints <= 0) continue;

                    double grossSellEdge = sellBoxEdgePoints * lotSize;
                    double sellTurnover = (ce1.bid + pe1.ask + ce2.ask + pe2.bid) * lotSize;
                    double sellSttSell = (ce1.bid + pe2.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
                    double sellSttBuy = (pe1.ask + ce2.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
                    double sellStt = sellSttSell + sellSttBuy;
                    double sellBrokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
                    double sellExchange = sellTurnover * ArbitrageCosts.EXCHANGE_RATE;
                    double sellSebi = sellTurnover * ArbitrageCosts.SEBI_RATE;
                    double sellGst = (sellBrokerage + sellExchange + sellSebi) * ArbitrageCosts.GST_RATE;
                    double sellStamp = sellTurnover * ArbitrageCosts.STAMP_RATE;
                    double sellTotalCosts = sellStt + sellBrokerage + sellExchange + sellSebi + sellGst + sellStamp;
                    double netSellEdge = grossSellEdge - sellTotalCosts;

                    if (netSellEdge >= MIN_BOX_EDGE_AFTER_COSTS) {
                        long daysToExpiry = java.time.Duration.between(
                            LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays();

                        ArbitrageOpportunity opp = new ArbitrageOpportunity();
                        opp.underlying = underlying;
                        opp.strike = k1;
                        opp.type = "BOX_SPREAD";
                        opp.action = "SHORT BOX (" + k1 + "/" + k2 + ")";
                        opp.legs = String.format("SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %d CE @ %.1f | SELL %d PE @ %.1f",
                            k1, ce1.bid, k1, pe1.ask, k2, ce2.ask, k2, pe2.bid);
                        opp.description = String.format("Width=%d | Credit=%.1f | Net=%.1f", (int) width, sellBoxCredit, netSellEdge);
                        opp.spotPrice = spotPrice;
                        opp.futuresPrice = futuresPrice;
                        opp.cePrice = ce1.lastPrice;
                        opp.pePrice = pe1.lastPrice;
                        opp.ceBid = ce1.bid;
                        opp.ceAsk = ce1.ask;
                        opp.peBid = pe1.bid;
                        opp.peAsk = pe1.ask;
                        opp.edgePoints = Math.round(sellBoxEdgePoints * 100.0) / 100.0;
                        opp.edgeAfterCosts = Math.round(netSellEdge * 100.0) / 100.0;
                        opp.daysToExpiry = daysToExpiry;
                        opp.confidence = 95.0;

                        Map<String, Double> costs = new LinkedHashMap<>();
                        costs.put("stt", Math.round(sellStt * 100.0) / 100.0);
                        costs.put("brokerage", sellBrokerage);
                        costs.put("exchange", Math.round(sellExchange * 100.0) / 100.0);
                        costs.put("sebi", Math.round(sellSebi * 100.0) / 100.0);
                        costs.put("gst", Math.round(sellGst * 100.0) / 100.0);
                        costs.put("stamp", Math.round(sellStamp * 100.0) / 100.0);
                        costs.put("totalCosts", Math.round(sellTotalCosts * 100.0) / 100.0);
                        costs.put("netEdge", Math.round(netSellEdge * 100.0) / 100.0);
                        opp.costBreakdown = costs;
                        opp.legList = List.of(
                            leg(k1, "CE", "SELL", 1, ce1.bid), leg(k1, "PE", "BUY", 1, pe1.ask),
                            leg(k2, "CE", "BUY", 1, ce2.ask), leg(k2, "PE", "SELL", 1, pe2.bid)
                        );

                        opps.add(opp);
                    }
                }
            }

            log.info("Box spread scan for {}: {} strikes, {} combos, {} profitable (expiry={}, ATM={})",
                underlying, strikes.size(), (strikes.size() * (strikes.size()-1))/2, opps.size(), weeklyExpiry, atmStrike);
        } catch (Exception e) {
            log.error("Error calculating Box Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }

    /**
     * "Near-miss" boxes: NOT arbitrage -- a box's payoff at expiry is always exactly `width`
     * regardless of settlement, so any box priced below width already IS genuine riskless
     * arbitrage and is already surfaced in full by scanBoxSpread (its threshold is 0, no
     * filtering happens there). There is no "cheap but not quite arbitrage" tier for Box the
     * way there is for Butterfly/Vertical/Condor -- instead this surfaces combos that are
     * CLOSE to crossing into real arbitrage (net edge is negative but small), as a watchlist
     * for spreads worth monitoring if the quote narrows a little further. Purely informational.
     */
    public List<Map<String, Object>> scanNearMiss(String underlying, double maxGapPct) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        Map<String, String> spotKeys = Map.of(
            "NIFTY", "NSE:NIFTY 50",
            "BANKNIFTY", "NSE:NIFTY BANK",
            "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
            "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        List<Map<String, Object>> nearMisses = new ArrayList<>();
        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
                double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
                if (spot <= 0 && fut > 0) spot = fut;
                if (spot <= 0) continue;

                int step = OptionChainService.getStrikeStep(u);
                int atmStrike = (int) (Math.round(spot / step) * step);
                int lotSize = OptionChainService.getLotSize(u);

                LocalDate weeklyExpiry = optionChainService.getWeeklyExpiryDate(u);
                if (weeklyExpiry == null) continue;

                List<Integer> strikes = new ArrayList<>();
                for (int i = -3; i <= 3; i++) strikes.add(atmStrike + i * step);

                List<String> instruments = new ArrayList<>();
                for (int strike : strikes) {
                    instruments.add(optionChainService.buildNfoSymbol(u, weeklyExpiry, strike, "CE"));
                    instruments.add(optionChainService.buildNfoSymbol(u, weeklyExpiry, strike, "PE"));
                }
                Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

                for (int i = 0; i < strikes.size(); i++) {
                    for (int j = i + 1; j < strikes.size(); j++) {
                        int k1 = strikes.get(i);
                        int k2 = strikes.get(j);
                        double width = k2 - k1;

                        OptionChainService.OptionQuote ce1 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k1, "CE"));
                        OptionChainService.OptionQuote pe1 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k1, "PE"));
                        OptionChainService.OptionQuote ce2 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k2, "CE"));
                        OptionChainService.OptionQuote pe2 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k2, "PE"));
                        if (!isLiquid(ce1, lotSize) || !isLiquid(pe1, lotSize) || !isLiquid(ce2, lotSize) || !isLiquid(pe2, lotSize)) continue;
                        if (ce1.ask <= 0 || pe1.bid <= 0 || ce2.bid <= 0 || pe2.ask <= 0) continue;
                        if (ce1.bid <= 0 || pe1.ask <= 0 || ce2.ask <= 0 || pe2.bid <= 0) continue;

                        double buyBoxCost = ce1.ask - pe1.bid - ce2.bid + pe2.ask;
                        double buyTurnover = (ce1.ask + pe1.bid + ce2.bid + pe2.ask) * lotSize;
                        double buySttBuy = (ce1.ask + pe2.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
                        double buySttSell = (pe1.bid + ce2.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
                        double buyNetEdge = costsAdjustedEdge((width - buyBoxCost) * lotSize, buySttBuy + buySttSell, buyTurnover);

                    if (ce1.bid <= 0 || pe1.ask <= 0 || ce2.ask <= 0 || pe2.bid <= 0) continue;
                        double sellBoxCredit = ce1.bid - pe1.ask - ce2.ask + pe2.bid;
                        double sellTurnover = (ce1.bid + pe1.ask + ce2.ask + pe2.bid) * lotSize;
                        double sellSttSell = (ce1.bid + pe2.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
                        double sellSttBuy = (pe1.ask + ce2.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
                        double sellNetEdge = costsAdjustedEdge((sellBoxCredit - width) * lotSize, sellSttSell + sellSttBuy, sellTurnover);

                        // Already real arbitrage in either direction -- scanBoxSpread already
                        // surfaces this, skip it here.
                        if (buyNetEdge >= 0 || sellNetEdge >= 0) continue;

                        boolean buyCloser = buyNetEdge >= sellNetEdge;
                        double gap = -Math.max(buyNetEdge, sellNetEdge);
                        double widthValue = width * lotSize;
                        double gapPct = widthValue > 0 ? gap / widthValue : 1.0;
                        if (gapPct > maxGapPct) continue;

                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("underlying", u);
                        m.put("strikes", k1 + "/" + k2);
                        m.put("k1", k1); m.put("k2", k2);
                        m.put("width", (int) width);
                        m.put("direction", buyCloser ? "LONG BOX" : "SHORT BOX");
                        m.put("gapInr", Math.round(gap * 100.0) / 100.0);
                        m.put("gapPct", Math.round(gapPct * 1000.0) / 10.0);
                        m.put("lotSize", lotSize);
                        m.put("spotPrice", spot);
                        m.put("daysToExpiry", java.time.Duration.between(LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays());
                        m.put("expiryDate", weeklyExpiry.toString());
                        m.put("legs", buyCloser
                            ? String.format("BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d PE @ %.1f",
                                k1, ce1.ask, k1, pe1.bid, k2, ce2.bid, k2, pe2.ask)
                            : String.format("SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %d CE @ %.1f | SELL %d PE @ %.1f",
                                k1, ce1.bid, k1, pe1.ask, k2, ce2.ask, k2, pe2.bid));
                        m.put("disclaimer", "Not arbitrage yet -- this box needs the quoted spread to narrow by roughly the gap shown before it becomes a genuine riskless box. Purely a watchlist, no execution wiring.");
                        nearMisses.add(m);
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Box near-miss for {}: {}", u, e.getMessage(), e);
            }
        }

        // Closest to real arbitrage first (smallest gap).
        nearMisses.sort((a, b) -> Double.compare((double) a.get("gapInr"), (double) b.get("gapInr")));
        return nearMisses;
    }

    private double costsAdjustedEdge(double grossEdge, double stt, double turnover) {
        double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
        double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
        double sebi = turnover * ArbitrageCosts.SEBI_RATE;
        double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
        double stamp = turnover * ArbitrageCosts.STAMP_RATE;
        double totalCosts = stt + brokerage + exchange + sebi + gst + stamp;
        return grossEdge - totalCosts;
    }

    static Map<String, Object> leg(int strike, String optionType, String side, int qty, double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strike", strike);
        m.put("optionType", optionType);
        m.put("side", side);
        m.put("qty", qty);
        m.put("price", price);
        return m;
    }
}
