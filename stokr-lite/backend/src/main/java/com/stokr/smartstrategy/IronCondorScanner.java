package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class IronCondorScanner {

    private static final Logger log = LoggerFactory.getLogger(IronCondorScanner.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public IronCondorScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Iron Condor scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("score", 0)).doubleValue(),
            ((Number) a.getOrDefault("score", 0)).doubleValue()));
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
        for (int i = -10; i <= 10; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Iron Condor = Sell OTM Put + Buy further OTM Put (bull put spread)
        //             + Sell OTM Call + Buy further OTM Call (bear call spread)
        for (int putDist = 2; putDist <= 6; putDist++) {
            for (int callDist = 2; callDist <= 6; callDist++) {
                for (int wingWidth = 1; wingWidth <= 3; wingWidth++) {
                    int putSellStrike = atmStrike - putDist * step;
                    int putBuyStrike = putSellStrike - wingWidth * step;
                    int callSellStrike = atmStrike + callDist * step;
                    int callBuyStrike = callSellStrike + wingWidth * step;

                    OptionChainService.OptionQuote putSellQ = getQuote(quotes, underlying, expiry, putSellStrike, "PE");
                    OptionChainService.OptionQuote putBuyQ = getQuote(quotes, underlying, expiry, putBuyStrike, "PE");
                    OptionChainService.OptionQuote callSellQ = getQuote(quotes, underlying, expiry, callSellStrike, "CE");
                    OptionChainService.OptionQuote callBuyQ = getQuote(quotes, underlying, expiry, callBuyStrike, "CE");
                    if (putSellQ == null || putBuyQ == null || callSellQ == null || callBuyQ == null) continue;
                    if (putSellQ.bid <= 0 || callSellQ.bid <= 0) continue;

                    double netCredit = (putSellQ.bid - putBuyQ.ask) + (callSellQ.bid - callBuyQ.ask);
                    if (netCredit <= 0) continue;

                    double putWingPts = putSellStrike - putBuyStrike;
                    double callWingPts = callBuyStrike - callSellStrike;
                    double maxWingWidth = Math.max(putWingPts, callWingPts);
                    double maxLoss = maxWingWidth - netCredit;
                    if (maxLoss <= 0) continue;

                    double creditRs = netCredit * lotSize;
                    double maxLossRs = maxLoss * lotSize;
                    double rewardRiskRatio = netCredit / maxLoss;

                    // Filter: credit must be >= 30% of wing width for decent R:R
                    if (rewardRiskRatio < 0.30) continue;

                    double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 50;

                    double breakEvenDown = putSellStrike - netCredit;
                    double breakEvenUp = callSellStrike + netCredit;
                    double breakEvenRange = breakEvenUp - breakEvenDown;
                    double breakEvenRangePct = breakEvenRange / spot * 100;

                    // Scoring: higher is better
                    // 1. Credit/risk ratio (0-35 pts)
                    double rrScore = Math.min(35, rewardRiskRatio * 70);
                    // 2. Breakeven range width as % of spot (0-30 pts)
                    double rangeScore = Math.min(30, breakEvenRangePct * 5);
                    // 3. Liquidity: OI and volume of short legs (0-20 pts)
                    double avgOI = (putSellQ.openInterest + callSellQ.openInterest) / 2.0;
                    double oiScore = Math.min(20, avgOI / 50000.0 * 20);
                    // 4. Symmetry bonus: balanced distances from ATM (0-15 pts)
                    double asymmetry = Math.abs(putDist - callDist);
                    double symScore = Math.max(0, 15 - asymmetry * 5);

                    double score = round2(rrScore + rangeScore + oiScore + symScore);

                    double putDistPct = (spot - putSellStrike) / spot * 100;
                    double callDistPct = (callSellStrike - spot) / spot * 100;
                    double estimatedWinRate = Math.min(85, 50 + (putDistPct + callDistPct) * 2);

                    Map<String, Object> opp = new LinkedHashMap<>();
                    opp.put("strategyType", "IRON_CONDOR");
                    opp.put("underlying", underlying);
                    opp.put("putBuyStrike", putBuyStrike);
                    opp.put("putSellStrike", putSellStrike);
                    opp.put("callSellStrike", callSellStrike);
                    opp.put("callBuyStrike", callBuyStrike);
                    opp.put("expiry", expiry.toString());
                    opp.put("expiryDate", expiry.toString());
                    opp.put("dte", dte);
                    opp.put("lotSize", lotSize);
                    opp.put("spotPrice", round2(spot));
                    opp.put("putSellPrice", round2(putSellQ.bid));
                    opp.put("putBuyPrice", round2(putBuyQ.ask));
                    opp.put("callSellPrice", round2(callSellQ.bid));
                    opp.put("callBuyPrice", round2(callBuyQ.ask));
                    opp.put("credit", round2(netCredit));
                    opp.put("creditRs", round2(creditRs));
                    opp.put("maxLoss", round2(maxLossRs + txnCost));
                    opp.put("maxLossPoints", round2(maxLoss));
                    opp.put("wingWidth", round2(maxWingWidth));
                    opp.put("rewardRiskRatio", round2(rewardRiskRatio));
                    opp.put("breakEvenDown", round2(breakEvenDown));
                    opp.put("breakEvenUp", round2(breakEvenUp));
                    opp.put("breakEvenRange", round2(breakEvenRange));
                    opp.put("breakEvenRangePct", round2(breakEvenRangePct));
                    opp.put("estimatedWinRate", round2(estimatedWinRate));
                    opp.put("score", score);
                    opp.put("riskLevel", rewardRiskRatio >= 0.5 ? "LOW" : "MEDIUM");
                    opp.put("scenarioFlat", round2(creditRs - txnCost));
                    opp.put("scenarioUp", round2(-maxLossRs - txnCost));
                    opp.put("scenarioDown", round2(-maxLossRs - txnCost));
                    opp.put("action", String.format("BUY %dPE | SELL %dPE | SELL %dCE | BUY %dCE — credit %.1f",
                        putBuyStrike, putSellStrike, callSellStrike, callBuyStrike, netCredit));
                    opp.put("legList", List.of(
                        Map.of("strike", putBuyStrike, "optionType", "PE", "side", "BUY", "qty", 1, "price", putBuyQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, putBuyStrike, "PE")),
                        Map.of("strike", putSellStrike, "optionType", "PE", "side", "SELL", "qty", 1, "price", putSellQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, putSellStrike, "PE")),
                        Map.of("strike", callSellStrike, "optionType", "CE", "side", "SELL", "qty", 1, "price", callSellQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, callSellStrike, "CE")),
                        Map.of("strike", callBuyStrike, "optionType", "CE", "side", "BUY", "qty", 1, "price", callBuyQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, callBuyStrike, "CE"))
                    ));
                    opp.put("edgePoints", round2(netCredit));
                    opp.put("edgeAfterCosts", round2(creditRs - txnCost));
                    results.add(opp);
                }
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
