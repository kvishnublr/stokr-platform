package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 4-leg box spread scanner (LONG/SHORT).
 * Fair value ≈ PV(K2 − K1). Executable prices use bid/ask only (no LTP fallback).
 */
@Service
public class BoxSpreadService {

    private static final Logger log = LoggerFactory.getLogger(BoxSpreadService.class);
    private static final double RISK_FREE = 0.065;
    /** Minimum net edge after costs (₹) to publish. */
    private static final double MIN_BOX_EDGE_AFTER_COSTS = 150.0;
    /** Skip option legs with spread wider than this (pts). */
    private static final double MAX_OPTION_SPREAD = 25.0;
    /** Approx costs for 4 legs. */
    private static final double BOX_COSTS = 160.0;

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

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
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : 0;

                // ATM reference only — never invent a fake futures for display basis
                double ref = fut > 0 ? fut : spot;
                if (ref <= 0) {
                    log.warn("No spot/fut for {} — skip box scan", u);
                    continue;
                }
                if (spot <= 0) spot = ref;
                if (fut <= 0) fut = 0; // leave 0 rather than cloning spot

                List<ArbitrageOpportunity> opps = scanBoxSpreadForUnderlying(u, spot, fut > 0 ? fut : ref);
                if (opps != null && !opps.isEmpty()) {
                    historyService.saveOpportunities(opps, u, "BOX_SPREAD");
                    for (ArbitrageOpportunity opp : opps) {
                        Map<String, Object> map = opp.toMap();
                        map.put("strategyType", "BOX_SPREAD");
                        map.put("guaranteedFill", false);
                        if (opp.costBreakdown != null) {
                            map.put("lowerStrike", opp.costBreakdown.get("lowerStrike"));
                            map.put("upperStrike", opp.costBreakdown.get("upperStrike"));
                            map.put("boxCost", opp.costBreakdown.get("boxCost"));
                            map.put("payoff", opp.costBreakdown.get("payoff"));
                            map.put("fairValue", opp.costBreakdown.get("fairValue"));
                            map.put("width", opp.costBreakdown.get("width"));
                        }
                        map.put("boxEdgeInr", opp.edgeAfterCosts);
                        results.add(map);
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Box Spread for {}: {}", u, e.getMessage(), e);
            }
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("edgeAfterCosts", 0)).doubleValue(),
                ((Number) a.getOrDefault("edgeAfterCosts", 0)).doubleValue()));
        return results;
    }

    public List<ArbitrageOpportunity> scanBoxSpreadForUnderlying(String underlying, double spotPrice, double futuresPrice) {
        List<ArbitrageOpportunity> opps = new ArrayList<>();
        try {
            double ref = futuresPrice > 0 ? futuresPrice : spotPrice;
            int atmStrike = optionChainService.getATMStrike(underlying, ref);
            // Near ATM pairs only (±4 strikes)
            List<Integer> strikes = new ArrayList<>();
            int step = OptionChainService.getStrikeStep(underlying);
            for (int i = -4; i <= 4; i++) {
                strikes.add(atmStrike + i * step);
            }

            // Use weekly expiry (same series for all 4 legs) — box must be same expiry
            LocalDate expiryDate = optionChainService.getWeeklyExpiryDate(underlying);
            double daysToExpiry = Math.max(0.5,
                    Duration.between(LocalDate.now(ZoneId.of("Asia/Kolkata")).atStartOfDay(),
                            expiryDate.atStartOfDay()).toDays());
            double years = daysToExpiry / 365.0;

            List<String> instruments = new ArrayList<>();
            for (int s : strikes) {
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiryDate, s, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiryDate, s, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            int lotSize = OptionChainService.getLotSize(underlying);
            log.info("Box scan {}: ATM={} expiry={} quotes={} strikes={}",
                    underlying, atmStrike, expiryDate, quotes.size(), strikes.size());

            for (int i = 0; i < strikes.size(); i++) {
                for (int j = i + 1; j < strikes.size(); j++) {
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(j);
                    double width = k2 - k1;
                    if (width <= 0) continue;

                    OptionChainService.OptionQuote ce1 = firstQuote(quotes, underlying, expiryDate, k1, "CE");
                    OptionChainService.OptionQuote pe1 = firstQuote(quotes, underlying, expiryDate, k1, "PE");
                    OptionChainService.OptionQuote ce2 = firstQuote(quotes, underlying, expiryDate, k2, "CE");
                    OptionChainService.OptionQuote pe2 = firstQuote(quotes, underlying, expiryDate, k2, "PE");
                    if (ce1 == null || pe1 == null || ce2 == null || pe2 == null) continue;

                    // Require full executable book — no LTP fallback
                    if (!hasBook(ce1) || !hasBook(pe1) || !hasBook(ce2) || !hasBook(pe2)) continue;
                    if (spread(ce1) > MAX_OPTION_SPREAD || spread(pe1) > MAX_OPTION_SPREAD) continue;
                    if (spread(ce2) > MAX_OPTION_SPREAD || spread(pe2) > MAX_OPTION_SPREAD) continue;

                    double fair = width * Math.exp(-RISK_FREE * years);

                    // LONG BOX: buy k1 synthetic, sell k2 synthetic
                    // BUY k1 CE @ask, SELL k1 PE @bid, SELL k2 CE @bid, BUY k2 PE @ask
                    double longCost = ce1.ask - pe1.bid - ce2.bid + pe2.ask;
                    double longEdgePts = fair - longCost;
                    double longNet = longEdgePts * lotSize - BOX_COSTS;

                    // SHORT BOX: opposite sides
                    double shortProceeds = ce1.bid - pe1.ask - ce2.ask + pe2.bid; // cash if short (often negative cost)
                    // Short box edge: receive longCost-equivalent; profit if proceeds > fair
                    // Executable short entry credit = -shortProceeds when using: SELL k1CE@bid, BUY k1PE@ask, BUY k2CE@ask, SELL k2PE@bid
                    double shortCredit = ce1.bid - pe1.ask - ce2.ask + pe2.bid;
                    // Wait: short box = sell the long box = SELL k1 CE, BUY k1 PE, BUY k2 CE, SELL k2 PE
                    // Credit received = ce1.bid - pe1.ask - ce2.ask + pe2.bid  ... same formula as shortCredit
                    // Edge vs fair: credit - fair (want credit > fair)
                    double shortEdgePts = shortCredit - fair;
                    double shortNet = shortEdgePts * lotSize - BOX_COSTS;

                    if (longNet >= MIN_BOX_EDGE_AFTER_COSTS) {
                        opps.add(buildBox(underlying, k1, k2, width, fair, longCost, longEdgePts, longNet,
                                spotPrice, futuresPrice, daysToExpiry, expiryDate, lotSize, true,
                                ce1, pe1, ce2, pe2));
                    }
                    if (shortNet >= MIN_BOX_EDGE_AFTER_COSTS) {
                        opps.add(buildBox(underlying, k1, k2, width, fair, shortCredit, shortEdgePts, shortNet,
                                spotPrice, futuresPrice, daysToExpiry, expiryDate, lotSize, false,
                                ce1, pe1, ce2, pe2));
                    }
                }
            }

            opps.sort((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts));
            log.info("Box scan {} done: {} opportunities", underlying, opps.size());
        } catch (Exception e) {
            log.error("Error calculating Box Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }

    private ArbitrageOpportunity buildBox(String underlying, int k1, int k2, double width, double fair,
                                          double boxCost, double edgePts, double netEdge,
                                          double spot, double fut, double dte, LocalDate expiry,
                                          int lotSize, boolean isLong,
                                          OptionChainService.OptionQuote ce1,
                                          OptionChainService.OptionQuote pe1,
                                          OptionChainService.OptionQuote ce2,
                                          OptionChainService.OptionQuote pe2) {
        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = underlying;
        opp.strike = k1;
        opp.type = "BOX_SPREAD";
        opp.action = (isLong ? "LONG BOX" : "SHORT BOX") + " (" + k1 + "/" + k2 + ")";
        opp.spotPrice = spot;
        opp.futuresPrice = fut;
        opp.cePrice = ce1.lastPrice;
        opp.pePrice = pe1.lastPrice;
        opp.ceBid = ce1.bid;
        opp.ceAsk = ce1.ask;
        opp.peBid = pe1.bid;
        opp.peAsk = pe1.ask;
        opp.edgePoints = Math.round(edgePts * 10.0) / 10.0;
        opp.edgeAfterCosts = Math.round(netEdge * 10.0) / 10.0;
        opp.confidence = Math.min(99.0, 55.0 + Math.abs(edgePts) * 2.0);
        opp.daysToExpiry = dte;
        opp.expiryDate = expiry;
        opp.detectedAt = LocalDateTime.now();
        if (isLong) {
            opp.legs = String.format(
                    "BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d PE @ %.1f",
                    k1, ce1.ask, k1, pe1.bid, k2, ce2.bid, k2, pe2.ask);
            opp.description = "Long box cheap vs PV(width) — buy low strike synthetic / sell high strike synthetic";
        } else {
            opp.legs = String.format(
                    "SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %d CE @ %.1f | SELL %d PE @ %.1f",
                    k1, ce1.bid, k1, pe1.ask, k2, ce2.ask, k2, pe2.bid);
            opp.description = "Short box rich vs PV(width) — sell low strike synthetic / buy high strike synthetic";
        }

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("lowerStrike", (double) k1);
        costs.put("upperStrike", (double) k2);
        costs.put("width", width);
        costs.put("fairValue", Math.round(fair * 100.0) / 100.0);
        costs.put("boxCost", Math.round(boxCost * 100.0) / 100.0);
        costs.put("payoff", width); // expiry payoff of long box in points
        costs.put("lotSize", (double) lotSize);
        costs.put("costsInr", BOX_COSTS);
        costs.put("netInr", opp.edgeAfterCosts);
        opp.costBreakdown = costs;
        return opp;
    }

    private OptionChainService.OptionQuote firstQuote(Map<String, OptionChainService.OptionQuote> quotes,
                                                      String underlying, LocalDate expiry, int strike, String type) {
        for (String sym : optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiry, strike, type)) {
            OptionChainService.OptionQuote q = quotes.get(sym);
            if (q != null && (q.lastPrice > 0 || q.bid > 0 || q.ask > 0)) return q;
        }
        return null;
    }

    private static boolean hasBook(OptionChainService.OptionQuote q) {
        return q.bid > 0 && q.ask > 0 && q.ask >= q.bid;
    }

    private static double spread(OptionChainService.OptionQuote q) {
        return q.ask - q.bid;
    }
}
