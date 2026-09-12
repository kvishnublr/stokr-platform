package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class RatioButterflyScanner {

    private static final Logger log = LoggerFactory.getLogger(RatioButterflyScanner.class);
    private static final double RISK_FREE_RATE = 0.065;

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public RatioButterflyScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Ratio butterfly scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("riskReward", 0)).doubleValue(),
            ((Number) a.getOrDefault("riskReward", 0)).doubleValue()));
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
        for (int i = -2; i <= 8; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        for (String optType : List.of("CE", "PE")) {
            int dir = "CE".equals(optType) ? 1 : -1;
            for (int bodyOffset = 1; bodyOffset <= 4; bodyOffset++) {
                int buyStrike = atmStrike;
                int sellStrike = atmStrike + dir * bodyOffset * step;
                int farBuyStrike = atmStrike + dir * (bodyOffset * 2) * step;

                OptionChainService.OptionQuote buyQ = getQuote(quotes, underlying, expiry, buyStrike, optType);
                OptionChainService.OptionQuote sellQ = getQuote(quotes, underlying, expiry, sellStrike, optType);
                OptionChainService.OptionQuote farBuyQ = getQuote(quotes, underlying, expiry, farBuyStrike, optType);
                if (buyQ == null || sellQ == null || farBuyQ == null) continue;
                if (buyQ.ask <= 0 || sellQ.bid <= 0 || farBuyQ.ask <= 0) continue;

                double cost = buyQ.ask - 3 * sellQ.bid + 2 * farBuyQ.ask;
                int wingWidth = Math.abs(sellStrike - buyStrike);
                double maxProfit = (wingWidth - cost) * lotSize;
                // Max loss: 1 uncovered short beyond the far wing. Risk = wingWidth + net debit paid.
                double maxLoss = (wingWidth + Math.max(cost, 0)) * lotSize;
                if (maxLoss <= 0) maxLoss = 1;

                if (cost > step * 0.3) continue;

                double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 6 + 50;
                maxLoss += txnCost;
                double riskReward = maxProfit / maxLoss;
                if (riskReward < 3) continue;

                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("strategyType", "RATIO_BUTTERFLY");
                opp.put("underlying", underlying);
                opp.put("optionType", optType);
                opp.put("buyStrike", buyStrike);
                opp.put("sellStrike", sellStrike);
                opp.put("farBuyStrike", farBuyStrike);
                opp.put("expiry", expiry.toString());
                opp.put("expiryDate", expiry.toString());
                opp.put("dte", dte);
                opp.put("lotSize", lotSize);
                opp.put("spotPrice", round2(spot));
                opp.put("buyPrice", round2(buyQ.ask));
                opp.put("sellPrice", round2(sellQ.bid));
                opp.put("farBuyPrice", round2(farBuyQ.ask));
                opp.put("netCost", round2(cost));
                opp.put("netCostRs", round2(cost * lotSize));
                opp.put("maxProfit", round2(maxProfit));
                opp.put("maxLoss", round2(maxLoss));
                opp.put("riskReward", round2(riskReward));
                opp.put("sweetSpot", sellStrike);
                opp.put("breakEvenLow", "CE".equals(optType) ? round2(buyStrike + cost) : round2(farBuyStrike + cost));
                opp.put("breakEvenHigh", "CE".equals(optType) ? round2(farBuyStrike - cost) : round2(buyStrike - cost));
                opp.put("action", String.format("BUY 1x%d%s @ %.1f | SELL 3x%d%s @ %.1f | BUY 2x%d%s @ %.1f",
                    buyStrike, optType, buyQ.ask, sellStrike, optType, sellQ.bid, farBuyStrike, optType, farBuyQ.ask));
                opp.put("legList", List.of(
                    Map.of("strike", buyStrike, "optionType", optType, "side", "BUY", "qty", 1, "price", buyQ.ask,
                        "symbol", getSymbol(quotes, underlying, expiry, buyStrike, optType)),
                    Map.of("strike", sellStrike, "optionType", optType, "side", "SELL", "qty", 3, "price", sellQ.bid,
                        "symbol", getSymbol(quotes, underlying, expiry, sellStrike, optType)),
                    Map.of("strike", farBuyStrike, "optionType", optType, "side", "BUY", "qty", 2, "price", farBuyQ.ask,
                        "symbol", getSymbol(quotes, underlying, expiry, farBuyStrike, optType))
                ));
                opp.put("edgePoints", round2(Math.abs(cost)));
                opp.put("edgeAfterCosts", round2(maxProfit - txnCost));
                results.add(opp);
            }
        }
        return results;
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
