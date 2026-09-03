package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Vertical spread no-arbitrage bound scanner. For strikes K1 < K2 on the same expiry:
 *   Call spread: 0 <= C(K1) - C(K2) <= (K2 - K1)
 *   Put spread:  0 <= P(K2) - P(K1) <= (K2 - K1)
 * Both bounds hold by pure convexity/no-arbitrage of option payoffs -- no interest-rate,
 * futures, or synthetic-price model involved (the same reason Box Spread is safe: this is
 * enforced at settlement by the payoff structure itself, not by a formula that can be wrong).
 * Model-free, same risk family as BoxSpreadService. Requires real live bid/ask -- no
 * fallback to last-traded price (see BoxSpreadService for why that matters).
 */
@Service
public class VerticalSpreadService {
    private boolean isLiquid(OptionChainService.OptionQuote q, int lotSize) {
        if (q == null) return false;
        if (q.volume <= 0) return false;
        if (q.bidQty < lotSize || q.askQty < lotSize) return false;
        return true;
    }


    private static final Logger log = LoggerFactory.getLogger(VerticalSpreadService.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private static final double MIN_EDGE_AFTER_COSTS = 0.0;

    public VerticalSpreadService(OptionChainService optionChainService,
                                  OptionArbHistoryService historyService,
                                  ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanVerticalSpread(String underlying) {
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
                    var saved = historyService.saveOpportunities(opps, u, "VERTICAL_SPREAD");
                    for (int idx = 0; idx < opps.size(); idx++) {
                        Map<String, Object> map = opps.get(idx).toMap();
                        map.put("strategyType", "VERTICAL_SPREAD");
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
                log.error("Error scanning Vertical Spread for {}: {}", u, e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * "Cheap vertical" candidates: NOT arbitrage, just debit spreads where the cost paid is a
     * small fraction of the width -- a directional bet (profits if price clears the breakeven
     * by expiry), not a guaranteed-profit position. Unlike butterflies these are directional,
     * not a pin bet, so POP here is P(settle beyond breakeven), not P(settle within a range).
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
                if (spot <= 0 && fut > 0) spot = fut;
                if (spot <= 0) continue;

                int step = OptionChainService.getStrikeStep(u);
                int atmStrike = (int) (Math.round(spot / step) * step);
                int lotSize = OptionChainService.getLotSize(u);

                LocalDate monthlyExpiry = optionChainService.getMonthlyExpiryDate(u);
                if (monthlyExpiry == null) continue;

                List<Integer> strikes = new ArrayList<>();
                for (int i = -4; i <= 4; i++) strikes.add(atmStrike + i * step);

                List<String> instruments = new ArrayList<>();
                for (int strike : strikes) {
                    instruments.add(optionChainService.buildNfoSymbol(u, monthlyExpiry, strike, "CE"));
                    instruments.add(optionChainService.buildNfoSymbol(u, monthlyExpiry, strike, "PE"));
                }
                Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

                double yearsToExpiry = Math.max(
                    java.time.Duration.between(LocalDate.now().atStartOfDay(), monthlyExpiry.atStartOfDay()).toDays(), 0.5) / 365.0;

                for (int i = 0; i < strikes.size(); i++) {
                    for (int j = i + 1; j < strikes.size(); j++) {
                        int k1 = strikes.get(i);
                        int k2 = strikes.get(j);
                        double width = k2 - k1;

                        OptionChainService.OptionQuote c1 = quotes.get(optionChainService.buildNfoSymbol(u, monthlyExpiry, k1, "CE"));
                        OptionChainService.OptionQuote c2 = quotes.get(optionChainService.buildNfoSymbol(u, monthlyExpiry, k2, "CE"));
                        OptionChainService.OptionQuote p1 = quotes.get(optionChainService.buildNfoSymbol(u, monthlyExpiry, k1, "PE"));
                        OptionChainService.OptionQuote p2 = quotes.get(optionChainService.buildNfoSymbol(u, monthlyExpiry, k2, "PE"));

                        // Call debit spread: BUY K1 CE, SELL K2 CE -- profits above breakeven.
                        if (c1 != null && c2 != null && c1.ask > 0 && c2.bid > 0) {
                            double cost = c1.ask - c2.bid;
                            if (cost > 0 && cost < width) {
                                double costRatio = cost / width;
                                if (costRatio <= maxCostRatio) {
                                    double breakeven = k1 + cost;
                                    double iv = BlackScholesCalculator.impliedVolatility(
                                        (c1.bid + c1.ask) / 2.0, spot, k1, yearsToExpiry, ArbitrageCosts.RISK_FREE_RATE, true, 0.01, 50);
                                    double pop = BlackScholesCalculator.probabilityAbove(spot, breakeven, yearsToExpiry, ArbitrageCosts.RISK_FREE_RATE, iv);
                                    candidates.add(buildCandidate(u, "CE", k1, k2, width, cost, costRatio, lotSize,
                                        breakeven, null, pop, iv, spot, monthlyExpiry,
                                        String.format("BUY %d CE @ %.1f | SELL %d CE @ %.1f", k1, c1.ask, k2, c2.bid),
                                        List.of(leg(k1, "CE", "BUY", 1, c1.ask), leg(k2, "CE", "SELL", 1, c2.bid)),
                                        c1.ask, c2.bid, ArbitrageCosts.STT_OPTION_BUY, ArbitrageCosts.STT_OPTION_SELL));
                                }
                            }
                        }

                        // Put debit spread: BUY K2 PE, SELL K1 PE -- profits below breakeven.
                        if (p1 != null && p2 != null && p1.bid > 0 && p2.ask > 0) {
                            double cost = p2.ask - p1.bid;
                            if (cost > 0 && cost < width) {
                                double costRatio = cost / width;
                                if (costRatio <= maxCostRatio) {
                                    double breakeven = k2 - cost;
                                    double iv = BlackScholesCalculator.impliedVolatility(
                                        (p2.bid + p2.ask) / 2.0, spot, k2, yearsToExpiry, ArbitrageCosts.RISK_FREE_RATE, false, 0.01, 50);
                                    double pop = BlackScholesCalculator.probabilityBelow(spot, breakeven, yearsToExpiry, ArbitrageCosts.RISK_FREE_RATE, iv);
                                    candidates.add(buildCandidate(u, "PE", k1, k2, width, cost, costRatio, lotSize,
                                        null, breakeven, pop, iv, spot, monthlyExpiry,
                                        String.format("BUY %d PE @ %.1f | SELL %d PE @ %.1f", k2, p2.ask, k1, p1.bid),
                                        List.of(leg(k2, "PE", "BUY", 1, p2.ask), leg(k1, "PE", "SELL", 1, p1.bid)),
                                        p2.ask, p1.bid, ArbitrageCosts.STT_OPTION_BUY, ArbitrageCosts.STT_OPTION_SELL));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Vertical candidates for {}: {}", u, e.getMessage(), e);
            }
        }

        candidates.sort((a, b) -> Double.compare((double) b.get("pop"), (double) a.get("pop")));
        return candidates;
    }

    private Map<String, Object> buildCandidate(String u, String optionType, int k1, int k2, double width,
                                                double cost, double costRatio, int lotSize,
                                                Double breakevenUpper, Double breakevenLower,
                                                double pop, double iv, double spot, LocalDate monthlyExpiry,
                                                String legs, List<Map<String, Object>> legList,
                                                double buyPrice, double sellPrice, double sttBuyRate, double sttSellRate) {
        double grossLoss = cost * lotSize;
        double grossProfit = (width - cost) * lotSize;

        double turnover = (buyPrice + sellPrice) * lotSize;
        double stt = buyPrice * lotSize * sttBuyRate + sellPrice * lotSize * sttSellRate;
        double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 2;
        double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
        double sebi = turnover * ArbitrageCosts.SEBI_RATE;
        double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
        double stamp = turnover * ArbitrageCosts.STAMP_RATE;
        double entryCosts = stt + brokerage + exchange + sebi + gst + stamp;

        double maxLoss = grossLoss + entryCosts;
        double maxProfit = Math.max(0, grossProfit - entryCosts);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("underlying", u);
        m.put("optionType", optionType);
        m.put("strikes", k1 + "/" + k2);
        m.put("k1", k1); m.put("k2", k2);
        m.put("strike", k1);
        m.put("action", "BUY_SPREAD " + optionType + " (" + k1 + "/" + k2 + ")");
        m.put("strategyType", "VERTICAL_SPREAD");
        m.put("width", (int) width);
        m.put("costPerLot", Math.round(cost * 100.0) / 100.0);
        m.put("costRatio", Math.round(costRatio * 1000.0) / 1000.0);
        m.put("maxLoss", Math.round(maxLoss * 100.0) / 100.0);
        m.put("maxProfit", Math.round(maxProfit * 100.0) / 100.0);
        m.put("grossMaxLoss", Math.round(grossLoss * 100.0) / 100.0);
        m.put("grossMaxProfit", Math.round(grossProfit * 100.0) / 100.0);
        m.put("entryCosts", Math.round(entryCosts * 100.0) / 100.0);
        m.put("marginEstimate", Math.round(maxLoss * 100.0) / 100.0);
        m.put("lotSize", lotSize);
        m.put("riskFreeRate", ArbitrageCosts.RISK_FREE_RATE);
        m.put("edgeAfterCosts", Math.round(maxProfit * 100.0) / 100.0);
        m.put("riskReward", maxLoss > 0 ? Math.round((maxProfit / maxLoss) * 100.0) / 100.0 : 0);
        m.put("breakevenLower", breakevenLower != null ? Math.round(breakevenLower * 100.0) / 100.0 : null);
        m.put("breakevenUpper", breakevenUpper != null ? Math.round(breakevenUpper * 100.0) / 100.0 : null);
        m.put("direction", breakevenUpper != null ? "Profits ABOVE breakeven" : "Profits BELOW breakeven");
        m.put("pop", Math.round(pop * 1000.0) / 10.0);
        m.put("impliedVol", Math.round(iv * 1000.0) / 10.0);
        m.put("spotPrice", spot);
        m.put("daysToExpiry", java.time.Duration.between(LocalDate.now().atStartOfDay(), monthlyExpiry.atStartOfDay()).toDays());
        m.put("expiryDate", monthlyExpiry.toString());
        m.put("legs", legs);
        m.put("legList", legList);
        m.put("disclaimer", "Not arbitrage. Directional bet -- POP is a Black-Scholes model estimate from current implied volatility, not a backtested or historical win rate.");
        return m;
    }

    private List<ArbitrageOpportunity> scanForUnderlying(String underlying, double spotPrice, double futuresPrice) {
        List<ArbitrageOpportunity> opps = new ArrayList<>();
        try {
            int step = OptionChainService.getStrikeStep(underlying);
            int atmStrike = (int) (Math.round(spotPrice / step) * step);
            int lotSize = OptionChainService.getLotSize(underlying);

            LocalDate monthlyExpiry = optionChainService.getMonthlyExpiryDate(underlying);
            if (monthlyExpiry == null) return opps;

            List<Integer> strikes = new ArrayList<>();
            for (int i = -4; i <= 4; i++) strikes.add(atmStrike + i * step);

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.add(optionChainService.buildNfoSymbol(underlying, monthlyExpiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, monthlyExpiry, strike, "PE"));
            }
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

            for (int i = 0; i < strikes.size(); i++) {
                for (int j = i + 1; j < strikes.size(); j++) {
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(j);
                    double width = k2 - k1;

                    OptionChainService.OptionQuote ce1 = quotes.get(optionChainService.buildNfoSymbol(underlying, monthlyExpiry, k1, "CE"));
                    OptionChainService.OptionQuote ce2 = quotes.get(optionChainService.buildNfoSymbol(underlying, monthlyExpiry, k2, "CE"));
                    OptionChainService.OptionQuote pe1 = quotes.get(optionChainService.buildNfoSymbol(underlying, monthlyExpiry, k1, "PE"));
                    OptionChainService.OptionQuote pe2 = quotes.get(optionChainService.buildNfoSymbol(underlying, monthlyExpiry, k2, "PE"));

                    checkCallVertical(opps, underlying, monthlyExpiry, k1, k2, width, ce1, ce2, lotSize, spotPrice, futuresPrice);
                    checkPutVertical(opps, underlying, monthlyExpiry, k1, k2, width, pe1, pe2, lotSize, spotPrice, futuresPrice);
                }
            }

            log.info("Vertical spread scan for {}: {} strikes, {} combos, {} opportunities (expiry={}, ATM={})",
                underlying, strikes.size(), (strikes.size() * (strikes.size() - 1)) / 2, opps.size(), monthlyExpiry, atmStrike);
        } catch (Exception e) {
            log.error("Error calculating Vertical Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }

    /** Call spread bound: 0 <= C(K1) - C(K2) <= width, for K1 < K2. */
    private void checkCallVertical(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                                    int k1, int k2, double width,
                                    OptionChainService.OptionQuote c1, OptionChainService.OptionQuote c2,
                                    int lotSize, double spot, double fut) {
        if (c1 == null || c2 == null) return;
        if (c1.bid <= 0 || c1.ask <= 0 || c2.bid <= 0 || c2.ask <= 0) return;

        // Direction A: sell the spread (sell K1 call, buy K2 call) for more credit than the max
        // possible liability (width) -- net edge = credit - width.
        double sellCredit = c1.bid - c2.ask;
        if (sellCredit > width) {
            double turnover = (c1.bid + c2.ask) * lotSize;
            double stt = c1.bid * lotSize * ArbitrageCosts.STT_OPTION_SELL + c2.ask * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            addOpportunity(opps, underlying, expiry, k1, k2, width, "CE", "SELL_SPREAD",
                sellCredit - width, stt, turnover, lotSize, spot, fut,
                String.format("SELL %d CE @ %.1f | BUY %d CE @ %.1f", k1, c1.bid, k2, c2.ask),
                List.of(leg(k1, "CE", "SELL", 1, c1.bid), leg(k2, "CE", "BUY", 1, c2.ask)));
            return;
        }

        // Direction B: buy the spread (buy K1 call, sell K2 call) for a net credit (negative
        // cost) -- you're paid now to hold a position worth >= 0 at expiry.
        double buyCost = c1.ask - c2.bid;
        if (buyCost < 0) {
            double turnover = (c1.ask + c2.bid) * lotSize;
            double stt = c1.ask * lotSize * ArbitrageCosts.STT_OPTION_BUY + c2.bid * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            addOpportunity(opps, underlying, expiry, k1, k2, width, "CE", "BUY_SPREAD",
                -buyCost, stt, turnover, lotSize, spot, fut,
                String.format("BUY %d CE @ %.1f | SELL %d CE @ %.1f", k1, c1.ask, k2, c2.bid),
                List.of(leg(k1, "CE", "BUY", 1, c1.ask), leg(k2, "CE", "SELL", 1, c2.bid)));
        }
    }

    /** Put spread bound: 0 <= P(K2) - P(K1) <= width, for K1 < K2. */
    private void checkPutVertical(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                                   int k1, int k2, double width,
                                   OptionChainService.OptionQuote p1, OptionChainService.OptionQuote p2,
                                   int lotSize, double spot, double fut) {
        if (p1 == null || p2 == null) return;
        if (p1.bid <= 0 || p1.ask <= 0 || p2.bid <= 0 || p2.ask <= 0) return;

        // Direction A: sell the spread (sell K2 put, buy K1 put) for more credit than width.
        double sellCredit = p2.bid - p1.ask;
        if (sellCredit > width) {
            double turnover = (p2.bid + p1.ask) * lotSize;
            double stt = p2.bid * lotSize * ArbitrageCosts.STT_OPTION_SELL + p1.ask * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            addOpportunity(opps, underlying, expiry, k1, k2, width, "PE", "SELL_SPREAD",
                sellCredit - width, stt, turnover, lotSize, spot, fut,
                String.format("SELL %d PE @ %.1f | BUY %d PE @ %.1f", k2, p2.bid, k1, p1.ask),
                List.of(leg(k2, "PE", "SELL", 1, p2.bid), leg(k1, "PE", "BUY", 1, p1.ask)));
            return;
        }

        // Direction B: buy the spread (buy K2 put, sell K1 put) for a net credit.
        double buyCost = p2.ask - p1.bid;
        if (buyCost < 0) {
            double turnover = (p2.ask + p1.bid) * lotSize;
            double stt = p2.ask * lotSize * ArbitrageCosts.STT_OPTION_BUY + p1.bid * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            addOpportunity(opps, underlying, expiry, k1, k2, width, "PE", "BUY_SPREAD",
                -buyCost, stt, turnover, lotSize, spot, fut,
                String.format("BUY %d PE @ %.1f | SELL %d PE @ %.1f", k2, p2.ask, k1, p1.bid),
                List.of(leg(k2, "PE", "BUY", 1, p2.ask), leg(k1, "PE", "SELL", 1, p1.bid)));
        }
    }

    private void addOpportunity(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                                 int k1, int k2, double width, String optionType, String direction,
                                 double edgePoints, double stt, double turnover,
                                 int lotSize, double spot, double fut, String legs,
                                 List<Map<String, Object>> legList) {
        double grossEdge = edgePoints * lotSize;

        // 2-leg fee estimate: one buy + one sell option order, real STT/brokerage/exchange/GST/stamp,
        // computed by the caller per-direction (STT rate depends on which leg is actually bought
        // vs sold, which differs between the two violation directions).
        double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 2;
        double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
        double sebi = turnover * ArbitrageCosts.SEBI_RATE;
        double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
        double stamp = turnover * ArbitrageCosts.STAMP_RATE;
        double totalCosts = stt + brokerage + exchange + sebi + gst + stamp;
        double netEdge = grossEdge - totalCosts;

        if (netEdge < MIN_EDGE_AFTER_COSTS) return;

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = underlying;
        opp.strike = k1;
        opp.type = "VERTICAL_SPREAD";
        opp.action = direction + " " + optionType + " (" + k1 + "/" + k2 + ")";
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
