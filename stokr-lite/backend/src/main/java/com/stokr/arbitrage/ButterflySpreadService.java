package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Butterfly spread no-arbitrage bound scanner. For equally-spaced strikes K1 < K2 < K3
 * (K2-K1 = K3-K2 = width), same expiry, same type:
 *   0 <= C(K1) - 2*C(K2) + C(K3) <= width   (call butterfly)
 *   0 <= P(K1) - 2*P(K2) + P(K3) <= width   (put butterfly)
 * Payoff at expiry is 0 at the tails and exactly `width` at K2 -- a pure convexity bound
 * enforced by settlement, same model-free family as BoxSpreadService and
 * VerticalSpreadService. No interest-rate or futures-price assumption. Requires real live
 * bid/ask on all 3 strikes -- no fallback to last-traded price.
 */
@Service
public class ButterflySpreadService {

    private static final Logger log = LoggerFactory.getLogger(ButterflySpreadService.class);
    private static final double MIN_EDGE_AFTER_COSTS = 0.0;

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    public ButterflySpreadService(OptionChainService optionChainService,
                                   OptionArbHistoryService historyService,
                                   ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanButterflySpread(String underlying) {
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

                List<ArbitrageOpportunity> opps = scanForUnderlying(u, spot, fut);
                if (!opps.isEmpty()) {
                    var saved = historyService.saveOpportunities(opps, u, "BUTTERFLY_SPREAD");
                    for (int idx = 0; idx < opps.size(); idx++) {
                        Map<String, Object> map = opps.get(idx).toMap();
                        map.put("strategyType", "BUTTERFLY_SPREAD");
                        map.put("status", "RUNNING");
                        map.put("guaranteedFill", true);
                        map.put("scanTime", java.time.LocalDateTime.now().toString());
                        map.put("detectedAt", java.time.LocalDateTime.now().toString());
                        if (idx < saved.size()) {
                            map.put("id", saved.get(idx).getId());
                            map.put("expiryDate", saved.get(idx).getExpiryDate() != null ? saved.get(idx).getExpiryDate().toString() : null);
                        }
                        results.add(map);
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Butterfly Spread for {}: {}", u, e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * "Cheap butterfly" candidates: NOT arbitrage (no bound violation), just butterflies
     * where the debit paid is a small fraction of the width -- a defined-risk bet that price
     * pins near the center strike by expiry, evaluated here purely for the user to inspect.
     * No backtested win-rate, no execution wiring -- this is a discovery/evaluation tool, not
     * a recommended strategy.
     */
    public List<Map<String, Object>> scanCandidates(String underlying, double maxCostRatio) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        Map<String, String> spotKeys = Map.of(
            "NIFTY", "NSE:NIFTY 50",
            "BANKNIFTY", "NSE:NIFTY BANK",
            "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
            "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
                double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
                // Spot feed occasionally has a transient hiccup while futures still resolves --
                // fall back to futures as an ATM-strike proxy, matching every other scanner here.
                if (spot <= 0 && fut > 0) spot = fut;
                if (spot <= 0) continue;

                int step = OptionChainService.getStrikeStep(u);
                int atmStrike = (int) (Math.round(spot / step) * step);
                int lotSize = OptionChainService.getLotSize(u);

                LocalDate weeklyExpiry = optionChainService.getWeeklyExpiryDate(u);
                if (weeklyExpiry == null) continue;

                List<Integer> strikes = new ArrayList<>();
                for (int i = -4; i <= 4; i++) strikes.add(atmStrike + i * step);

                List<String> instruments = new ArrayList<>();
                for (int strike : strikes) {
                    instruments.add(optionChainService.buildNfoSymbol(u, weeklyExpiry, strike, "CE"));
                    instruments.add(optionChainService.buildNfoSymbol(u, weeklyExpiry, strike, "PE"));
                }
                Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

                for (int c = 0; c < strikes.size(); c++) {
                    for (int n = 1; c - n >= 0 && c + n < strikes.size(); n++) {
                        int k1 = strikes.get(c - n);
                        int k2 = strikes.get(c);
                        int k3 = strikes.get(c + n);
                        double width = k2 - k1;

                        for (String optionType : List.of("CE", "PE")) {
                            OptionChainService.OptionQuote q1 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k1, optionType));
                            OptionChainService.OptionQuote q2 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k2, optionType));
                            OptionChainService.OptionQuote q3 = quotes.get(optionChainService.buildNfoSymbol(u, weeklyExpiry, k3, optionType));
                            if (q1 == null || q2 == null || q3 == null) continue;
                            if (q1.ask <= 0 || q2.bid <= 0 || q3.ask <= 0) continue;

                            double cost = q1.ask - 2 * q2.bid + q3.ask;
                            // Only genuinely-priced (cost > 0) butterflies -- cost <= 0 is a real
                            // arbitrage violation and already surfaced by scanButterflySpread.
                            if (cost <= 0 || cost >= width) continue;
                            double costRatio = cost / width;
                            if (costRatio > maxCostRatio) continue;

                            double grossLoss = cost * lotSize;
                            double grossProfit = (width - cost) * lotSize;

                            // Real entry transaction costs (STT/brokerage/exchange/SEBI/GST/stamp),
                            // same formula as the main arbitrage scanner's BUY_FLY branch -- these
                            // candidates were previously shown gross, overstating real profit and
                            // understating real max loss.
                            double turnover = (q1.ask + 2 * q2.bid + q3.ask) * lotSize;
                            double sttBuy = (q1.ask + q3.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
                            double sttSell = (2 * q2.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
                            double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
                            double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
                            double sebi = turnover * ArbitrageCosts.SEBI_RATE;
                            double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
                            double stamp = turnover * ArbitrageCosts.STAMP_RATE;
                            double entryCosts = sttBuy + sttSell + brokerage + exchange + sebi + gst + stamp;

                            double maxLoss = grossLoss + entryCosts;
                            double maxProfit = Math.max(0, grossProfit - entryCosts);
                            double breakevenLower = k1 + cost;
                            double breakevenUpper = k3 - cost;
                            // Conservative margin estimate for a bought (debit) defined-risk combo:
                            // worst-case cash outflow (premium + costs). Real broker margin for the
                            // short middle leg may differ -- verify with your broker before trading.
                            double marginEstimate = maxLoss;

                            // Model probability of profit: IV from the center strike's mid price,
                            // then P(breakevenLower <= settlement <= breakevenUpper) under the
                            // standard Black-Scholes lognormal assumption. A theoretical figure
                            // driven by current IV, not a backtested/historical win rate.
                            double yearsToExpiry = Math.max(
                                java.time.Duration.between(LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays(), 0.5) / 365.0;
                            double centerMid = (q2.bid + q2.ask) / 2.0;
                            double iv = BlackScholesCalculator.impliedVolatility(
                                centerMid, spot, k2, yearsToExpiry, ArbitrageCosts.RISK_FREE_RATE,
                                "CE".equals(optionType), 0.01, 50);
                            double pop = BlackScholesCalculator.probabilityInRange(
                                spot, breakevenLower, breakevenUpper, yearsToExpiry, ArbitrageCosts.RISK_FREE_RATE, iv);

                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("underlying", u);
                            m.put("optionType", optionType);
                            m.put("strikes", k1 + "/" + k2 + "/" + k3);
                            m.put("k1", k1); m.put("k2", k2); m.put("k3", k3);
                            m.put("strike", k2);
                            m.put("action", "BUY_FLY " + optionType + " (" + k1 + "/" + k2 + "/" + k3 + ")");
                            m.put("strategyType", "BUTTERFLY_SPREAD");
                            m.put("width", (int) width);
                            m.put("costPerLot", Math.round(cost * 100.0) / 100.0);
                            m.put("costRatio", Math.round(costRatio * 1000.0) / 1000.0);
                            m.put("maxLoss", Math.round(maxLoss * 100.0) / 100.0);
                            m.put("maxProfit", Math.round(maxProfit * 100.0) / 100.0);
                            m.put("grossMaxLoss", Math.round(grossLoss * 100.0) / 100.0);
                            m.put("grossMaxProfit", Math.round(grossProfit * 100.0) / 100.0);
                            m.put("entryCosts", Math.round(entryCosts * 100.0) / 100.0);
                            m.put("marginEstimate", Math.round(marginEstimate * 100.0) / 100.0);
                            m.put("lotSize", lotSize);
                            m.put("riskFreeRate", ArbitrageCosts.RISK_FREE_RATE);
                            // Used as the auto-exit/stop-loss target-edge if this candidate is
                            // ever traded (not a real arbitrage edge -- this is max profit potential).
                            m.put("edgeAfterCosts", Math.round(maxProfit * 100.0) / 100.0);
                            m.put("riskReward", maxLoss > 0 ? Math.round((maxProfit / maxLoss) * 100.0) / 100.0 : 0);
                            m.put("pinStrike", k2);
                            m.put("breakevenLower", Math.round(breakevenLower * 100.0) / 100.0);
                            m.put("breakevenUpper", Math.round(breakevenUpper * 100.0) / 100.0);
                            m.put("pop", Math.round(pop * 1000.0) / 10.0);
                            m.put("impliedVol", Math.round(iv * 1000.0) / 10.0);
                            m.put("spotPrice", spot);
                            m.put("daysToExpiry", java.time.Duration.between(LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays());
                            m.put("expiryDate", weeklyExpiry.toString());
                            m.put("legs", String.format("BUY %d %s @ %.1f | SELL 2x %d %s @ %.1f | BUY %d %s @ %.1f",
                                k1, optionType, q1.ask, k2, optionType, q2.bid, k3, optionType, q3.ask));
                            m.put("legList", List.of(leg(k1, optionType, "BUY", 1, q1.ask), leg(k2, optionType, "SELL", 2, q2.bid), leg(k3, optionType, "BUY", 1, q3.ask)));
                            m.put("disclaimer", "Not arbitrage. POP is a Black-Scholes model estimate from current implied volatility, not a backtested or historical win rate -- evaluate before trading.");
                            candidates.add(m);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Butterfly candidates for {}: {}", u, e.getMessage(), e);
            }
        }

        // Highest model probability-of-profit first, so the "safest" (by this one model
        // metric) candidates surface without the user having to sort manually.
        candidates.sort((a, b) -> Double.compare((double) b.get("pop"), (double) a.get("pop")));
        return candidates;
    }

    private List<ArbitrageOpportunity> scanForUnderlying(String underlying, double spotPrice, double futuresPrice) {
        List<ArbitrageOpportunity> opps = new ArrayList<>();
        try {
            int step = OptionChainService.getStrikeStep(underlying);
            int atmStrike = (int) (Math.round(spotPrice / step) * step);
            int lotSize = OptionChainService.getLotSize(underlying);

            LocalDate weeklyExpiry = optionChainService.getWeeklyExpiryDate(underlying);
            if (weeklyExpiry == null) return opps;

            List<Integer> strikes = new ArrayList<>();
            for (int i = -4; i <= 4; i++) strikes.add(atmStrike + i * step);

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.add(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, strike, "PE"));
            }
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

            int center = strikes.indexOf(atmStrike);
            int combos = 0;
            for (int c = 0; c < strikes.size(); c++) {
                for (int n = 1; c - n >= 0 && c + n < strikes.size(); n++) {
                    combos++;
                    int k1 = strikes.get(c - n);
                    int k2 = strikes.get(c);
                    int k3 = strikes.get(c + n);
                    double width = k2 - k1;

                    OptionChainService.OptionQuote ce1 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k1, "CE"));
                    OptionChainService.OptionQuote ce2 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k2, "CE"));
                    OptionChainService.OptionQuote ce3 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k3, "CE"));
                    OptionChainService.OptionQuote pe1 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k1, "PE"));
                    OptionChainService.OptionQuote pe2 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k2, "PE"));
                    OptionChainService.OptionQuote pe3 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k3, "PE"));

                    checkButterfly(opps, underlying, weeklyExpiry, k1, k2, k3, width, "CE", ce1, ce2, ce3, lotSize, spotPrice, futuresPrice);
                    checkButterfly(opps, underlying, weeklyExpiry, k1, k2, k3, width, "PE", pe1, pe2, pe3, lotSize, spotPrice, futuresPrice);
                }
            }

            log.info("Butterfly spread scan for {}: {} strikes, {} combos, {} opportunities (expiry={}, ATM={})",
                underlying, strikes.size(), combos, opps.size(), weeklyExpiry, atmStrike);
        } catch (Exception e) {
            log.error("Error calculating Butterfly Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }

    /** Bound: 0 <= Q(K1) - 2*Q(K2) + Q(K3) <= width, for either CE or PE at all 3 strikes. */
    private void checkButterfly(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                                 int k1, int k2, int k3, double width, String optionType,
                                 OptionChainService.OptionQuote q1, OptionChainService.OptionQuote q2, OptionChainService.OptionQuote q3,
                                 int lotSize, double spot, double fut) {
        if (q1 == null || q2 == null || q3 == null) return;
        if (q1.bid <= 0 || q1.ask <= 0 || q2.bid <= 0 || q2.ask <= 0 || q3.bid <= 0 || q3.ask <= 0) return;

        // Direction A: buy the fly (buy K1, sell 2xK2, buy K3) for a net credit (cost < 0) --
        // paid to enter a position that pays >= 0 at expiry.
        double buyCost = q1.ask - 2 * q2.bid + q3.ask;
        if (buyCost < 0) {
            double turnover = (q1.ask + 2 * q2.bid + q3.ask) * lotSize;
            double sttBuy = (q1.ask + q3.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            double sttSell = (2 * q2.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            addOpportunity(opps, underlying, expiry, k1, k2, k3, width, optionType, "BUY_FLY",
                -buyCost, sttBuy + sttSell, turnover, lotSize, spot, fut,
                String.format("BUY %d %s @ %.1f | SELL 2x %d %s @ %.1f | BUY %d %s @ %.1f",
                    k1, optionType, q1.ask, k2, optionType, q2.bid, k3, optionType, q3.ask),
                List.of(leg(k1, optionType, "BUY", 1, q1.ask), leg(k2, optionType, "SELL", 2, q2.bid), leg(k3, optionType, "BUY", 1, q3.ask)));
            return;
        }

        // Direction B: sell the fly (sell K1, buy 2xK2, sell K3) for more credit than width,
        // the max possible liability.
        double sellCredit = q1.bid - 2 * q2.ask + q3.bid;
        if (sellCredit > width) {
            double turnover = (q1.bid + 2 * q2.ask + q3.bid) * lotSize;
            double sttSell = (q1.bid + q3.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            double sttBuy = (2 * q2.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            addOpportunity(opps, underlying, expiry, k1, k2, k3, width, optionType, "SELL_FLY",
                sellCredit - width, sttBuy + sttSell, turnover, lotSize, spot, fut,
                String.format("SELL %d %s @ %.1f | BUY 2x %d %s @ %.1f | SELL %d %s @ %.1f",
                    k1, optionType, q1.bid, k2, optionType, q2.ask, k3, optionType, q3.bid),
                List.of(leg(k1, optionType, "SELL", 1, q1.bid), leg(k2, optionType, "BUY", 2, q2.ask), leg(k3, optionType, "SELL", 1, q3.bid)));
        }
    }

    private void addOpportunity(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                                 int k1, int k2, int k3, double width, String optionType, String direction,
                                 double edgePoints, double stt, double turnover,
                                 int lotSize, double spot, double fut, String legs,
                                 List<Map<String, Object>> legList) {
        double grossEdge = edgePoints * lotSize;

        // 4-leg fee estimate (1 + 2 + 1 orders): real STT/brokerage/exchange/GST/stamp,
        // computed from actual quoted prices (not a width-based proxy).
        double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
        double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
        double sebi = turnover * ArbitrageCosts.SEBI_RATE;
        double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
        double stamp = turnover * ArbitrageCosts.STAMP_RATE;
        double totalCosts = stt + brokerage + exchange + sebi + gst + stamp;
        double netEdge = grossEdge - totalCosts;

        if (netEdge < MIN_EDGE_AFTER_COSTS) return;

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = underlying;
        opp.strike = k2;
        opp.type = "BUTTERFLY_SPREAD";
        opp.action = direction + " " + optionType + " (" + k1 + "/" + k2 + "/" + k3 + ")";
        opp.legs = legs;
        opp.description = String.format("%s %s width=%d edge=%.1fpts net=Rs%.0f", optionType, direction, (int) width, edgePoints, netEdge);
        opp.spotPrice = spot;
        opp.futuresPrice = fut;
        opp.edgePoints = Math.round(edgePoints * 100.0) / 100.0;
        opp.edgeAfterCosts = Math.round(netEdge * 100.0) / 100.0;
        opp.daysToExpiry = java.time.Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays();
        opp.confidence = 95.0;

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("stt", Math.round(stt * 100.0) / 100.0);
        costs.put("brokerage", brokerage);
        costs.put("exchange", Math.round(exchange * 100.0) / 100.0);
        costs.put("sebi", Math.round(sebi * 100.0) / 100.0);
        costs.put("gst", Math.round(gst * 100.0) / 100.0);
        costs.put("stamp", Math.round(stamp * 100.0) / 100.0);
        costs.put("totalCosts", Math.round(totalCosts * 100.0) / 100.0);
        costs.put("netEdge", Math.round(netEdge * 100.0) / 100.0);
        opp.costBreakdown = costs;
        opp.legList = legList;
        opp.lotSize = lotSize;

        opps.add(opp);
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
