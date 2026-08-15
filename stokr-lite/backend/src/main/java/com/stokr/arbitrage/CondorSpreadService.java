package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Condor spread no-arbitrage bound scanner -- the 4-strike generalization of
 * ButterflySpreadService. For equally-spaced strikes K1<K2<K3<K4 (width = K2-K1 = K3-K2 =
 * K4-K3), same expiry/type, buying the OUTER strikes and selling the INNER strikes (all
 * calls, or all puts -- not the mixed call+put "Iron Condor" premium-selling strategy):
 *   0 <= Q(K1) - Q(K2) - Q(K3) + Q(K4) <= width
 * Payoff at expiry is 0 at the tails, ramps to `width` between K1-K2, stays flat at width
 * between K2-K3, ramps back to 0 between K3-K4 -- same convexity-at-settlement guarantee as
 * Box/Vertical/Butterfly, no interest-rate or futures-price assumption. Requires real live
 * bid/ask on all 4 strikes -- no fallback to last-traded price.
 */
@Service
public class CondorSpreadService {

    private static final Logger log = LoggerFactory.getLogger(CondorSpreadService.class);
    private static final double MIN_EDGE_AFTER_COSTS = 0.0;

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    public CondorSpreadService(OptionChainService optionChainService,
                                OptionArbHistoryService historyService,
                                ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanCondorSpread(String underlying) {
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
                    var saved = historyService.saveOpportunities(opps, u, "CONDOR_SPREAD");
                    for (int idx = 0; idx < opps.size(); idx++) {
                        Map<String, Object> map = opps.get(idx).toMap();
                        map.put("strategyType", "CONDOR_SPREAD");
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
                log.error("Error scanning Condor Spread for {}: {}", u, e.getMessage(), e);
            }
        }
        return results;
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

            int combos = 0;
            int n = strikes.size();
            for (int width = 1; width * 3 < n; width++) {
                for (int i = 0; i + 3 * width < n; i++) {
                    combos++;
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(i + width);
                    int k3 = strikes.get(i + 2 * width);
                    int k4 = strikes.get(i + 3 * width);
                    double w = k2 - k1;

                    OptionChainService.OptionQuote ce1 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k1, "CE"));
                    OptionChainService.OptionQuote ce2 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k2, "CE"));
                    OptionChainService.OptionQuote ce3 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k3, "CE"));
                    OptionChainService.OptionQuote ce4 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k4, "CE"));
                    OptionChainService.OptionQuote pe1 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k1, "PE"));
                    OptionChainService.OptionQuote pe2 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k2, "PE"));
                    OptionChainService.OptionQuote pe3 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k3, "PE"));
                    OptionChainService.OptionQuote pe4 = quotes.get(optionChainService.buildNfoSymbol(underlying, weeklyExpiry, k4, "PE"));

                    checkCondor(opps, underlying, weeklyExpiry, k1, k2, k3, k4, w, "CE", ce1, ce2, ce3, ce4, lotSize, spotPrice, futuresPrice);
                    checkCondor(opps, underlying, weeklyExpiry, k1, k2, k3, k4, w, "PE", pe1, pe2, pe3, pe4, lotSize, spotPrice, futuresPrice);
                }
            }

            log.info("Condor spread scan for {}: {} strikes, {} combos, {} opportunities (expiry={}, ATM={})",
                underlying, strikes.size(), combos, opps.size(), weeklyExpiry, atmStrike);
        } catch (Exception e) {
            log.error("Error calculating Condor Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }

    /** Bound: 0 <= Q(K1) - Q(K2) - Q(K3) + Q(K4) <= width (buy outer, sell inner). */
    private void checkCondor(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                              int k1, int k2, int k3, int k4, double width, String optionType,
                              OptionChainService.OptionQuote q1, OptionChainService.OptionQuote q2,
                              OptionChainService.OptionQuote q3, OptionChainService.OptionQuote q4,
                              int lotSize, double spot, double fut) {
        if (q1 == null || q2 == null || q3 == null || q4 == null) return;
        if (q1.bid <= 0 || q1.ask <= 0 || q2.bid <= 0 || q2.ask <= 0
            || q3.bid <= 0 || q3.ask <= 0 || q4.bid <= 0 || q4.ask <= 0) return;

        // Direction A: buy outer, sell inner, for a net credit (cost < 0) -- paid to enter a
        // position that pays >= 0 at expiry.
        double buyCost = q1.ask - q2.bid - q3.bid + q4.ask;
        if (buyCost < 0) {
            double turnover = (q1.ask + q2.bid + q3.bid + q4.ask) * lotSize;
            double sttBuy = (q1.ask + q4.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            double sttSell = (q2.bid + q3.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            addOpportunity(opps, underlying, expiry, k1, k2, k3, k4, width, optionType, "BUY_CONDOR",
                -buyCost, sttBuy + sttSell, turnover, lotSize, spot, fut,
                String.format("BUY %d %s @ %.1f | SELL %d %s @ %.1f | SELL %d %s @ %.1f | BUY %d %s @ %.1f",
                    k1, optionType, q1.ask, k2, optionType, q2.bid, k3, optionType, q3.bid, k4, optionType, q4.ask));
            return;
        }

        // Direction B: sell outer, buy inner, for more credit than width -- the max liability.
        double sellCredit = q1.bid - q2.ask - q3.ask + q4.bid;
        if (sellCredit > width) {
            double turnover = (q1.bid + q2.ask + q3.ask + q4.bid) * lotSize;
            double sttSell = (q1.bid + q4.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            double sttBuy = (q2.ask + q3.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            addOpportunity(opps, underlying, expiry, k1, k2, k3, k4, width, optionType, "SELL_CONDOR",
                sellCredit - width, sttBuy + sttSell, turnover, lotSize, spot, fut,
                String.format("SELL %d %s @ %.1f | BUY %d %s @ %.1f | BUY %d %s @ %.1f | SELL %d %s @ %.1f",
                    k1, optionType, q1.bid, k2, optionType, q2.ask, k3, optionType, q3.ask, k4, optionType, q4.bid));
        }
    }

    private void addOpportunity(List<ArbitrageOpportunity> opps, String underlying, LocalDate expiry,
                                 int k1, int k2, int k3, int k4, double width, String optionType, String direction,
                                 double edgePoints, double stt, double turnover,
                                 int lotSize, double spot, double fut, String legs) {
        double grossEdge = edgePoints * lotSize;

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
        opp.strike = k1;
        opp.type = "CONDOR_SPREAD";
        opp.action = direction + " " + optionType + " (" + k1 + "/" + k2 + "/" + k3 + "/" + k4 + ")";
        opp.legs = legs;
        opp.description = String.format("%s %s width=%d edge=%.1fpts net=Rs%.0f", optionType, direction, (int) width, edgePoints, netEdge);
        opp.spotPrice = spot;
        opp.futuresPrice = fut;
        opp.edgePoints = Math.round(edgePoints * 100.0) / 100.0;
        opp.edgeAfterCosts = Math.round(netEdge * 100.0) / 100.0;
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

        opps.add(opp);
    }
}
