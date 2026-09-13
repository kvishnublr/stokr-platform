package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class JadeLizardScanner {

    private static final Logger log = LoggerFactory.getLogger(JadeLizardScanner.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public JadeLizardScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Jade Lizard scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("creditRs", 0)).doubleValue(),
            ((Number) a.getOrDefault("creditRs", 0)).doubleValue()));
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
        for (int i = -16; i <= 10; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Iron Lizard = Sell OTM Put + Buy far OTM Put + Sell OTM Call + Buy further OTM Call
        // = Bull Put Spread + Bear Call Spread (with asymmetric widths)
        // Zero upside risk when: credit >= call spread width
        // Downside risk CAPPED by bought put (no naked exposure)
        for (int putDist = 2; putDist <= 5; putDist++) {
            for (int callDist = 2; callDist <= 5; callDist++) {
                for (int callSpreadWidth = 1; callSpreadWidth <= 3; callSpreadWidth++) {
                    int putSellStrike = atmStrike - putDist * step;
                    int callSellStrike = atmStrike + callDist * step;
                    int callBuyStrike = callSellStrike + callSpreadWidth * step;

                    // Put hedge: buy a far OTM put to cap downside but keep asymmetry
                    // Wider than call spread so we retain more credit (Jade Lizard character)
                    int putHedgeSteps = "BANKNIFTY".equals(underlying) ? 10 : 8;
                    int putBuyStrike = putSellStrike - putHedgeSteps * step;

                    OptionChainService.OptionQuote putSellQ = getQuote(quotes, underlying, expiry, putSellStrike, "PE");
                    OptionChainService.OptionQuote putBuyQ = getQuote(quotes, underlying, expiry, putBuyStrike, "PE");
                    OptionChainService.OptionQuote callSellQ = getQuote(quotes, underlying, expiry, callSellStrike, "CE");
                    OptionChainService.OptionQuote callBuyQ = getQuote(quotes, underlying, expiry, callBuyStrike, "CE");
                    if (putSellQ == null || putBuyQ == null || callSellQ == null || callBuyQ == null) continue;
                    if (putSellQ.bid <= 0 || callSellQ.bid <= 0 || callBuyQ.ask <= 0) continue;

                    double credit = putSellQ.bid - putBuyQ.ask + callSellQ.bid - callBuyQ.ask;
                    if (credit <= 0) continue;

                    double callSpreadWidthPts = callBuyStrike - callSellStrike;
                    double putSpreadWidthPts = putSellStrike - putBuyStrike;
                    boolean zeroUpsideRisk = credit >= callSpreadWidthPts;

                    // Max loss is now DEFINED on both sides
                    double maxLossDown = (putSpreadWidthPts - credit) * lotSize;
                    double maxLossUp = zeroUpsideRisk ? 0 : (callSpreadWidthPts - credit) * lotSize;
                    double maxLoss = Math.max(maxLossDown, maxLossUp);

                    double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 60;
                    double creditRs = credit * lotSize;
                    double netCreditRs = creditRs - txnCost;
                    if (netCreditRs < 50) continue;

                    // Only show good setups: credit > 30% of wider spread, or zero upside risk
                    double widerWidth = Math.max(callSpreadWidthPts, putSpreadWidthPts);
                    if (!zeroUpsideRisk && credit < widerWidth * 0.25) continue;

                    // Breakeven on downside
                    double breakEvenDown = putSellStrike - credit;

                    // Win rate estimate based on distance from spot
                    double putDistPct = (spot - putSellStrike) / spot * 100;
                    double callDistPct = (callSellStrike - spot) / spot * 100;
                    double minDistPct = Math.min(putDistPct, callDistPct);
                    double estimatedWinRate = Math.min(88, 50 + minDistPct * 5);

                    Map<String, Object> opp = new LinkedHashMap<>();
                    opp.put("strategyType", "JADE_LIZARD");
                    opp.put("underlying", underlying);
                    opp.put("putSellStrike", putSellStrike);
                    opp.put("putBuyStrike", putBuyStrike);
                    opp.put("callSellStrike", callSellStrike);
                    opp.put("callBuyStrike", callBuyStrike);
                    opp.put("expiry", expiry.toString());
                    opp.put("expiryDate", expiry.toString());
                    opp.put("dte", dte);
                    opp.put("lotSize", lotSize);
                    opp.put("spotPrice", round2(spot));
                    opp.put("putSellPrice", round2(putSellQ.bid));
                    opp.put("putBuyPrice", round2(putBuyQ.ask));
                    opp.put("putPrice", round2(putSellQ.bid));
                    opp.put("callSellPrice", round2(callSellQ.bid));
                    opp.put("callBuyPrice", round2(callBuyQ.ask));
                    opp.put("credit", round2(credit));
                    opp.put("creditRs", round2(creditRs));
                    opp.put("netCreditRs", round2(netCreditRs));
                    opp.put("callSpreadWidth", round2(callSpreadWidthPts));
                    opp.put("putSpreadWidth", round2(putSpreadWidthPts));
                    opp.put("zeroUpsideRisk", zeroUpsideRisk);
                    opp.put("maxLoss", round2(maxLoss + txnCost));
                    opp.put("maxLossDown", round2(maxLossDown + txnCost));
                    opp.put("maxLossUp", round2(maxLossUp));
                    opp.put("maxProfit", round2(netCreditRs));
                    opp.put("breakEvenDown", round2(breakEvenDown));
                    opp.put("estimatedWinRate", round2(estimatedWinRate));
                    opp.put("riskRewardRatio", round2(netCreditRs / (maxLoss + txnCost)));
                    opp.put("riskLevel", zeroUpsideRisk ? "ZERO_UPSIDE" : "LOW");
                    opp.put("scenarioFlat", round2(netCreditRs));
                    opp.put("scenarioUp", zeroUpsideRisk ? round2(netCreditRs) : round2(-maxLossUp - txnCost));
                    opp.put("scenarioDown", round2(-maxLossDown - txnCost));
                    opp.put("action", String.format("SELL %dPE @ %.1f | BUY %dPE @ %.1f | SELL %dCE @ %.1f | BUY %dCE @ %.1f",
                        putSellStrike, putSellQ.bid, putBuyStrike, putBuyQ.ask,
                        callSellStrike, callSellQ.bid, callBuyStrike, callBuyQ.ask));
                    opp.put("legList", List.of(
                        Map.of("strike", putSellStrike, "optionType", "PE", "side", "SELL", "qty", 1, "price", putSellQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, putSellStrike, "PE")),
                        Map.of("strike", putBuyStrike, "optionType", "PE", "side", "BUY", "qty", 1, "price", putBuyQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, putBuyStrike, "PE")),
                        Map.of("strike", callSellStrike, "optionType", "CE", "side", "SELL", "qty", 1, "price", callSellQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, callSellStrike, "CE")),
                        Map.of("strike", callBuyStrike, "optionType", "CE", "side", "BUY", "qty", 1, "price", callBuyQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, callBuyStrike, "CE"))
                    ));
                    opp.put("edgePoints", round2(credit));
                    opp.put("edgeAfterCosts", round2(netCreditRs));
                    opp.put("expectedPremiumRs", round2(netCreditRs));
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
