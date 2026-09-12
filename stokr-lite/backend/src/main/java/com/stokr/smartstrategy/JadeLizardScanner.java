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
        for (int i = -8; i <= 8; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Jade Lizard = Sell OTM Put + Sell OTM Call + Buy further OTM Call (bear call spread + short put)
        // Zero upside risk when: credit >= call spread width
        for (int putDist = 2; putDist <= 5; putDist++) {
            for (int callDist = 2; callDist <= 5; callDist++) {
                for (int callSpreadWidth = 1; callSpreadWidth <= 3; callSpreadWidth++) {
                    int putSellStrike = atmStrike - putDist * step;
                    int callSellStrike = atmStrike + callDist * step;
                    int callBuyStrike = callSellStrike + callSpreadWidth * step;

                    OptionChainService.OptionQuote putQ = getQuote(quotes, underlying, expiry, putSellStrike, "PE");
                    OptionChainService.OptionQuote callSellQ = getQuote(quotes, underlying, expiry, callSellStrike, "CE");
                    OptionChainService.OptionQuote callBuyQ = getQuote(quotes, underlying, expiry, callBuyStrike, "CE");
                    if (putQ == null || callSellQ == null || callBuyQ == null) continue;
                    if (putQ.bid <= 0 || callSellQ.bid <= 0 || callBuyQ.ask <= 0) continue;

                    double credit = putQ.bid + callSellQ.bid - callBuyQ.ask;
                    if (credit <= 0) continue;

                    double callSpreadWidthPts = callBuyStrike - callSellStrike;
                    boolean zeroUpsideRisk = credit >= callSpreadWidthPts;

                    // Max loss on downside = (putSellStrike - credit) * lotSize (like naked put minus credit)
                    double maxLossDown = (putSellStrike - credit) * lotSize;
                    // Max loss on upside = (callSpreadWidth - credit) * lotSize (if credit < spread width)
                    double maxLossUp = zeroUpsideRisk ? 0 : (callSpreadWidthPts - credit) * lotSize;

                    double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 3 + 40;
                    double creditRs = credit * lotSize;

                    // Only show good setups: credit > 40% of call spread width, or zero upside risk
                    if (!zeroUpsideRisk && credit < callSpreadWidthPts * 0.4) continue;

                    // Breakeven on downside
                    double breakEvenDown = putSellStrike - credit;

                    // Win rate estimate based on distance from spot
                    double putDistPct = (spot - putSellStrike) / spot * 100;
                    double estimatedWinRate = Math.min(90, 55 + putDistPct * 3);

                    Map<String, Object> opp = new LinkedHashMap<>();
                    opp.put("strategyType", "JADE_LIZARD");
                    opp.put("underlying", underlying);
                    opp.put("putSellStrike", putSellStrike);
                    opp.put("callSellStrike", callSellStrike);
                    opp.put("callBuyStrike", callBuyStrike);
                    opp.put("expiry", expiry.toString());
                    opp.put("expiryDate", expiry.toString());
                    opp.put("dte", dte);
                    opp.put("lotSize", lotSize);
                    opp.put("spotPrice", round2(spot));
                    opp.put("putPrice", round2(putQ.bid));
                    opp.put("callSellPrice", round2(callSellQ.bid));
                    opp.put("callBuyPrice", round2(callBuyQ.ask));
                    opp.put("credit", round2(credit));
                    opp.put("creditRs", round2(creditRs));
                    opp.put("callSpreadWidth", round2(callSpreadWidthPts));
                    opp.put("zeroUpsideRisk", zeroUpsideRisk);
                    opp.put("maxLossDown", round2(maxLossDown + txnCost));
                    opp.put("maxLossUp", round2(maxLossUp));
                    opp.put("breakEvenDown", round2(breakEvenDown));
                    opp.put("estimatedWinRate", round2(estimatedWinRate));
                    opp.put("riskLevel", zeroUpsideRisk ? "ZERO_UPSIDE" : "LOW");
                    opp.put("scenarioFlat", round2(creditRs - txnCost));
                    opp.put("scenarioUp", zeroUpsideRisk ? round2(creditRs - txnCost) : round2(-maxLossUp - txnCost));
                    opp.put("scenarioDown", round2(-maxLossDown - txnCost));
                    opp.put("action", String.format("SELL %dPE @ %.1f | SELL %dCE @ %.1f | BUY %dCE @ %.1f",
                        putSellStrike, putQ.bid, callSellStrike, callSellQ.bid, callBuyStrike, callBuyQ.ask));
                    opp.put("legList", List.of(
                        Map.of("strike", putSellStrike, "optionType", "PE", "side", "SELL", "qty", 1, "price", putQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, putSellStrike, "PE")),
                        Map.of("strike", callSellStrike, "optionType", "CE", "side", "SELL", "qty", 1, "price", callSellQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, callSellStrike, "CE")),
                        Map.of("strike", callBuyStrike, "optionType", "CE", "side", "BUY", "qty", 1, "price", callBuyQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, callBuyStrike, "CE"))
                    ));
                    opp.put("edgePoints", round2(credit));
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
