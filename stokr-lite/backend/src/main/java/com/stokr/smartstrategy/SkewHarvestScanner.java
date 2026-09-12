package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class SkewHarvestScanner {

    private static final Logger log = LoggerFactory.getLogger(SkewHarvestScanner.class);
    private static final double RISK_FREE_RATE = 0.065;

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public SkewHarvestScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Skew harvest scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("skewEdge", 0)).doubleValue(),
            ((Number) a.getOrDefault("skewEdge", 0)).doubleValue()));
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
        double years = dte / 365.0;
        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        List<String> instruments = new ArrayList<>();
        for (int i = -8; i <= 8; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Compute IV for OTM puts and calls to find skew
        Map<Integer, Double> putIVs = new LinkedHashMap<>();
        Map<Integer, Double> callIVs = new LinkedHashMap<>();
        for (int i = -6; i <= 6; i++) {
            int strike = atmStrike + i * step;
            OptionChainService.OptionQuote pq = getQuote(quotes, underlying, expiry, strike, "PE");
            OptionChainService.OptionQuote cq = getQuote(quotes, underlying, expiry, strike, "CE");
            if (pq != null && pq.lastPrice > 0) {
                double iv = BlackScholesCalculator.impliedVolatility(pq.lastPrice, spot, strike, years, RISK_FREE_RATE, false, 0.01, 50);
                if (iv > 0) putIVs.put(strike, iv * 100);
            }
            if (cq != null && cq.lastPrice > 0) {
                double iv = BlackScholesCalculator.impliedVolatility(cq.lastPrice, spot, strike, years, RISK_FREE_RATE, true, 0.01, 50);
                if (iv > 0) callIVs.put(strike, iv * 100);
            }
        }

        // Sell OTM put spread (overpriced) + Buy OTM call spread (underpriced)
        for (int putDist = 2; putDist <= 4; putDist++) {
            for (int callDist = 2; callDist <= 4; callDist++) {
                int putSellStrike = atmStrike - putDist * step;
                int putBuyStrike = atmStrike - (putDist + 1) * step;
                int callBuyStrike = atmStrike + callDist * step;
                int callSellStrike = atmStrike + (callDist + 1) * step;

                OptionChainService.OptionQuote psQ = getQuote(quotes, underlying, expiry, putSellStrike, "PE");
                OptionChainService.OptionQuote pbQ = getQuote(quotes, underlying, expiry, putBuyStrike, "PE");
                OptionChainService.OptionQuote cbQ = getQuote(quotes, underlying, expiry, callBuyStrike, "CE");
                OptionChainService.OptionQuote csQ = getQuote(quotes, underlying, expiry, callSellStrike, "CE");
                if (psQ == null || pbQ == null || cbQ == null || csQ == null) continue;
                if (psQ.bid <= 0 || pbQ.ask <= 0 || cbQ.ask <= 0 || csQ.bid <= 0) continue;

                double putSpreadCredit = psQ.bid - pbQ.ask;
                double callSpreadDebit = cbQ.ask - csQ.bid;
                double netCost = callSpreadDebit - putSpreadCredit;

                Double putSellIV = putIVs.get(putSellStrike);
                Double callBuyIV = callIVs.get(callBuyStrike);
                double skewEdge = (putSellIV != null && callBuyIV != null) ? putSellIV - callBuyIV : 0;

                if (skewEdge < 2) continue;

                int putSpreadWidth = Math.abs(putSellStrike - putBuyStrike);
                int callSpreadWidth = Math.abs(callSellStrike - callBuyStrike);
                double maxLossPut = (putSpreadWidth - putSpreadCredit) * lotSize;
                double maxProfitCall = (callSpreadWidth - callSpreadDebit) * lotSize;
                double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 40;

                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("strategyType", "SKEW_HARVEST");
                opp.put("underlying", underlying);
                opp.put("putSellStrike", putSellStrike);
                opp.put("putBuyStrike", putBuyStrike);
                opp.put("callBuyStrike", callBuyStrike);
                opp.put("callSellStrike", callSellStrike);
                opp.put("expiry", expiry.toString());
                opp.put("expiryDate", expiry.toString());
                opp.put("dte", dte);
                opp.put("lotSize", lotSize);
                opp.put("spotPrice", round2(spot));
                opp.put("putSpreadCredit", round2(putSpreadCredit));
                opp.put("callSpreadDebit", round2(callSpreadDebit));
                opp.put("netCost", round2(netCost));
                opp.put("netCostRs", round2(netCost * lotSize));
                opp.put("putSellIV", putSellIV != null ? round2(putSellIV) : 0);
                opp.put("callBuyIV", callBuyIV != null ? round2(callBuyIV) : 0);
                opp.put("skewEdge", round2(skewEdge));
                opp.put("maxLossPut", round2(maxLossPut + txnCost));
                opp.put("maxProfitCall", round2(maxProfitCall));
                opp.put("scenarioFlat", round2(putSpreadCredit * lotSize - txnCost));
                opp.put("scenarioUp", round2(maxProfitCall + putSpreadCredit * lotSize - txnCost));
                opp.put("scenarioDown", round2(-maxLossPut));
                opp.put("action", String.format("SELL %dPE @ %.1f | BUY %dPE @ %.1f | BUY %dCE @ %.1f | SELL %dCE @ %.1f",
                    putSellStrike, psQ.bid, putBuyStrike, pbQ.ask, callBuyStrike, cbQ.ask, callSellStrike, csQ.bid));
                opp.put("legList", List.of(
                    Map.of("strike", putSellStrike, "optionType", "PE", "side", "SELL", "qty", 1, "price", psQ.bid,
                        "symbol", getSymbol(quotes, underlying, expiry, putSellStrike, "PE")),
                    Map.of("strike", putBuyStrike, "optionType", "PE", "side", "BUY", "qty", 1, "price", pbQ.ask,
                        "symbol", getSymbol(quotes, underlying, expiry, putBuyStrike, "PE")),
                    Map.of("strike", callBuyStrike, "optionType", "CE", "side", "BUY", "qty", 1, "price", cbQ.ask,
                        "symbol", getSymbol(quotes, underlying, expiry, callBuyStrike, "CE")),
                    Map.of("strike", callSellStrike, "optionType", "CE", "side", "SELL", "qty", 1, "price", csQ.bid,
                        "symbol", getSymbol(quotes, underlying, expiry, callSellStrike, "CE"))
                ));
                opp.put("edgePoints", round2(skewEdge));
                opp.put("edgeAfterCosts", round2(putSpreadCredit * lotSize - txnCost));
                results.add(opp);
            }
        }
        return results;
    }

    private OptionChainService.OptionQuote getQuote(Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, int strike, String optType) {
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, expiry, strike, optType)) {
            if (quotes.containsKey(c) && quotes.get(c).lastPrice > 0) return quotes.get(c);
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
