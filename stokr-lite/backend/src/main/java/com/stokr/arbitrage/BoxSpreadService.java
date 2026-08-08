package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class BoxSpreadService {

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

                    if (ce1 == null || pe1 == null || ce2 == null || pe2 == null) continue;

                    double ce1Ask = ce1.ask > 0 ? ce1.ask : ce1.lastPrice;
                    double pe1Bid = pe1.bid > 0 ? pe1.bid : pe1.lastPrice;
                    double ce2Bid = ce2.bid > 0 ? ce2.bid : ce2.lastPrice;
                    double pe2Ask = pe2.ask > 0 ? pe2.ask : pe2.lastPrice;

                    if (ce1Ask <= 0 || pe1Bid <= 0 || ce2Bid <= 0 || pe2Ask <= 0) continue;

                    double buyBoxCost = ce1Ask - pe1Bid - ce2Bid + pe2Ask;
                    double buyBoxEdgePoints = width - buyBoxCost;
                    double grossBuyEdge = buyBoxEdgePoints * lotSize;
                    double brokerage = 120.0;
                    double stt = grossBuyEdge * 0.001;
                    double exchange = grossBuyEdge * 0.000345 * 4;
                    double sebi = grossBuyEdge * 0.00001;
                    double gst = (brokerage + exchange + sebi) * 0.18;
                    double ipft = grossBuyEdge * 0.00001;
                    double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;
                    double netBuyEdge = grossBuyEdge - totalCosts;

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
                        costs.put("ipft", Math.round(ipft * 100.0) / 100.0);
                        costs.put("totalCosts", Math.round(totalCosts * 100.0) / 100.0);
                        costs.put("netEdge", Math.round(netBuyEdge * 100.0) / 100.0);
                        opp.costBreakdown = costs;

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
}
