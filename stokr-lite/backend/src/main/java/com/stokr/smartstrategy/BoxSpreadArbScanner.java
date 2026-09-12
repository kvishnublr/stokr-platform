package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class BoxSpreadArbScanner {

    private static final Logger log = LoggerFactory.getLogger(BoxSpreadArbScanner.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public BoxSpreadArbScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Box spread scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("netEdgeRs", 0)).doubleValue(),
            ((Number) a.getOrDefault("netEdgeRs", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        LocalDate expiry = optionChainService.getNearestExpiry(underlying);
        long dte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays());
        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        List<String> instruments = new ArrayList<>();
        for (int i = -6; i <= 6; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Box = Bull Call Spread (K1→K2) + Bear Put Spread (K1→K2)
        // Theoretical value at expiry = K2 - K1 (always)
        // If cost < theoretical - txn costs → risk-free profit
        for (int i = -4; i <= 2; i++) {
            for (int width = 1; width <= 4; width++) {
                int k1 = atmStrike + i * step;
                int k2 = k1 + width * step;
                double theoreticalValue = k2 - k1;

                // Discount for time value of money
                double discountedValue = theoreticalValue * Math.exp(-ArbitrageCosts.RISK_FREE_RATE * dte / 365.0);

                OptionChainService.OptionQuote ce1 = getQuote(quotes, underlying, expiry, k1, "CE");
                OptionChainService.OptionQuote ce2 = getQuote(quotes, underlying, expiry, k2, "CE");
                OptionChainService.OptionQuote pe1 = getQuote(quotes, underlying, expiry, k1, "PE");
                OptionChainService.OptionQuote pe2 = getQuote(quotes, underlying, expiry, k2, "PE");
                if (ce1 == null || ce2 == null || pe1 == null || pe2 == null) continue;
                if (ce1.ask <= 0 || ce2.bid <= 0 || pe1.bid <= 0 || pe2.ask <= 0) continue;

                // Long box: Buy CE1 + Sell CE2 + Sell PE1 + Buy PE2
                double longBoxCost = (ce1.ask - ce2.bid) + (pe2.ask - pe1.bid);
                // Short box: Sell CE1 + Buy CE2 + Buy PE1 + Sell PE2
                double shortBoxCredit = (ce1.bid - ce2.ask) + (pe2.bid - pe1.ask);

                // Transaction costs: 4 legs, STT on sell side
                double sellPremiums = ce2.bid + pe1.bid;
                double buyPremiums = ce1.ask + pe2.ask;
                double turnover = (sellPremiums + buyPremiums) * lotSize;
                double sttSell = sellPremiums * lotSize * ArbitrageCosts.STT_OPTION_SELL;
                double sttBuy = buyPremiums * lotSize * ArbitrageCosts.STT_OPTION_BUY;
                double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
                double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
                double sebi = turnover * ArbitrageCosts.SEBI_RATE;
                double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
                double stamp = turnover * ArbitrageCosts.STAMP_RATE;
                double txnCost = sttSell + sttBuy + brokerage + exchange + sebi + gst + stamp;

                // Long box edge: you pay longBoxCost, receive theoreticalValue at expiry
                double longEdge = (discountedValue - longBoxCost) * lotSize - txnCost;
                // Short box edge: you receive shortBoxCredit, pay theoreticalValue at expiry
                double shortEdge = (-shortBoxCredit - discountedValue) * lotSize - txnCost;
                // shortBoxCredit is negative when there's credit, so flip:
                double shortEdgeAlt = (Math.abs(shortBoxCredit) - discountedValue) * lotSize - txnCost;

                // Only show edges >= ₹500 — smaller edges get eaten by slippage + txn costs
                if (longEdge >= 500) {
                    results.add(buildOpp(underlying, "LONG_BOX", k1, k2, expiry, dte, lotSize, spot,
                        ce1, ce2, pe1, pe2, longBoxCost, theoreticalValue, discountedValue,
                        longEdge, txnCost, quotes));
                }
                if (shortEdgeAlt >= 500) {
                    results.add(buildOpp(underlying, "SHORT_BOX", k1, k2, expiry, dte, lotSize, spot,
                        ce1, ce2, pe1, pe2, shortBoxCredit, theoreticalValue, discountedValue,
                        shortEdgeAlt, txnCost, quotes));
                }
            }
        }
        return results;
    }

    private Map<String, Object> buildOpp(String underlying, String boxType, int k1, int k2,
            LocalDate expiry, long dte, int lotSize, double spot,
            OptionChainService.OptionQuote ce1, OptionChainService.OptionQuote ce2,
            OptionChainService.OptionQuote pe1, OptionChainService.OptionQuote pe2,
            double cost, double theoreticalValue, double discountedValue,
            double netEdge, double txnCost, Map<String, OptionChainService.OptionQuote> quotes) {

        boolean isLong = "LONG_BOX".equals(boxType);
        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("strategyType", "BOX_SPREAD_ARB");
        opp.put("underlying", underlying);
        opp.put("boxType", boxType);
        opp.put("lowerStrike", k1);
        opp.put("upperStrike", k2);
        opp.put("expiry", expiry.toString());
        opp.put("expiryDate", expiry.toString());
        opp.put("dte", dte);
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", round2(spot));
        opp.put("theoreticalValue", round2(theoreticalValue));
        opp.put("discountedValue", round2(discountedValue));
        opp.put("boxCost", round2(cost));
        opp.put("netEdgeRs", round2(netEdge));
        opp.put("txnCostRs", round2(txnCost));
        opp.put("returnPct", round2(netEdge / (Math.abs(cost) * lotSize) * 100));
        opp.put("annualizedReturn", round2(netEdge / (Math.abs(cost) * lotSize) * 365.0 / dte * 100));
        opp.put("riskLevel", "ZERO");
        opp.put("maxProfit", round2(netEdge));
        opp.put("maxLoss", 0);

        if (isLong) {
            opp.put("action", String.format("BUY %dCE @ %.1f | SELL %dCE @ %.1f | SELL %dPE @ %.1f | BUY %dPE @ %.1f",
                k1, ce1.ask, k2, ce2.bid, k1, pe1.bid, k2, pe2.ask));
            opp.put("legList", List.of(
                Map.of("strike", k1, "optionType", "CE", "side", "BUY", "qty", 1, "price", ce1.ask,
                    "symbol", getSymbol(quotes, underlying, expiry, k1, "CE")),
                Map.of("strike", k2, "optionType", "CE", "side", "SELL", "qty", 1, "price", ce2.bid,
                    "symbol", getSymbol(quotes, underlying, expiry, k2, "CE")),
                Map.of("strike", k1, "optionType", "PE", "side", "SELL", "qty", 1, "price", pe1.bid,
                    "symbol", getSymbol(quotes, underlying, expiry, k1, "PE")),
                Map.of("strike", k2, "optionType", "PE", "side", "BUY", "qty", 1, "price", pe2.ask,
                    "symbol", getSymbol(quotes, underlying, expiry, k2, "PE"))
            ));
        } else {
            opp.put("action", String.format("SELL %dCE @ %.1f | BUY %dCE @ %.1f | BUY %dPE @ %.1f | SELL %dPE @ %.1f",
                k1, ce1.bid, k2, ce2.ask, k1, pe1.ask, k2, pe2.bid));
            opp.put("legList", List.of(
                Map.of("strike", k1, "optionType", "CE", "side", "SELL", "qty", 1, "price", ce1.bid,
                    "symbol", getSymbol(quotes, underlying, expiry, k1, "CE")),
                Map.of("strike", k2, "optionType", "CE", "side", "BUY", "qty", 1, "price", ce2.ask,
                    "symbol", getSymbol(quotes, underlying, expiry, k2, "CE")),
                Map.of("strike", k1, "optionType", "PE", "side", "BUY", "qty", 1, "price", pe1.ask,
                    "symbol", getSymbol(quotes, underlying, expiry, k1, "PE")),
                Map.of("strike", k2, "optionType", "PE", "side", "SELL", "qty", 1, "price", pe2.bid,
                    "symbol", getSymbol(quotes, underlying, expiry, k2, "PE"))
            ));
        }
        opp.put("edgePoints", round2(netEdge / lotSize));
        opp.put("edgeAfterCosts", round2(netEdge));
        return opp;
    }

    private OptionChainService.OptionQuote getQuote(Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, int strike, String optType) {
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, expiry, strike, optType)) {
            if (quotes.containsKey(c) && quotes.get(c).lastPrice > 0) {
                OptionChainService.OptionQuote q = quotes.get(c);
                if (q.bid <= 0) q.bid = q.lastPrice;
                if (q.ask <= 0) q.ask = q.lastPrice;
                return q;
            }
        }
        return null;
    }

    private String getSymbol(Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, int strike, String optType) {
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, expiry, strike, optType)) {
            if (quotes.containsKey(c) && quotes.get(c).lastPrice > 0) return c;
        }
        return underlying + strike + optType;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
