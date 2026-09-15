package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class AdaptiveStrategyScanner {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveStrategyScanner.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final MarketRegimeDetector regimeDetector;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public AdaptiveStrategyScanner(OptionChainService optionChainService,
                                    ZerodhaSpotPriceFetcher spotFetcher,
                                    MarketRegimeDetector regimeDetector) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.regimeDetector = regimeDetector;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Adaptive scan failed for {}: {}", u, e.getMessage(), e);
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("adaptiveScore", 0)).doubleValue(),
            ((Number) a.getOrDefault("adaptiveScore", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();

        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        // Get market regime
        MarketRegimeDetector.RegimeData regime = regimeDetector.getRegime(underlying);
        String regimeName = regime != null ? regime.regime().name() : "SIDEWAYS";
        double ivRank = regime != null ? regime.ivRank() : 50;
        double atmIV = regime != null ? regime.atmIV() : 15;

        LocalDate expiry = optionChainService.getNearestExpiry(underlying);
        long dte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays());
        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        // Fetch wide range of quotes
        List<String> instruments = new ArrayList<>();
        for (int i = -20; i <= 20; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Compute expected move from ATM straddle
        OptionChainService.OptionQuote atmCE = getQuote(quotes, underlying, expiry, atmStrike, "CE");
        OptionChainService.OptionQuote atmPE = getQuote(quotes, underlying, expiry, atmStrike, "PE");
        double straddlePrice = 0;
        if (atmCE != null && atmPE != null) {
            straddlePrice = atmCE.lastPrice + atmPE.lastPrice;
        }
        double expectedMovePoints = straddlePrice * 0.85; // ~85% of straddle is expected move
        double expectedMovePct = spot > 0 ? expectedMovePoints / spot * 100 : 1.0;
        int expectedMoveSteps = Math.max(2, (int) Math.round(expectedMovePoints / step));

        // Compute put-call skew
        double putSkew = 0;
        OptionChainService.OptionQuote otmPut = getQuote(quotes, underlying, expiry, atmStrike - 3 * step, "PE");
        OptionChainService.OptionQuote otmCall = getQuote(quotes, underlying, expiry, atmStrike + 3 * step, "CE");
        if (otmPut != null && otmCall != null && otmCall.lastPrice > 0) {
            putSkew = otmPut.lastPrice / otmCall.lastPrice;
        }

        // Detect trend from futures premium
        double futPremium = 0;
        if (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) {
            futPremium = (spotFut[1] - spot) / spot * 100;
        }

        log.info("ADAPTIVE [{}]: regime={}, ivRank={}, atmIV={}%, expectedMove={}pts ({}%), putSkew={}, futPremium={}%",
            underlying, regimeName, r2(ivRank), r2(atmIV), r2(expectedMovePoints), r2(expectedMovePct),
            r2(putSkew), r2(futPremium));

        double txnCostBase = ArbitrageCosts.PER_LEG_BROKERAGE;

        // === STRATEGY 1: IRON BUTTERFLY (High IV + Sideways/Neutral) ===
        // Sell ATM straddle, buy wings at expected move distance — maximum theta decay
        if (ivRank >= 40) {
            scanIronButterfly(results, quotes, underlying, expiry, dte, step, lotSize, atmStrike,
                spot, expectedMoveSteps, ivRank, regimeName, txnCostBase);
        }

        // === STRATEGY 2: RATIO SPREAD (High IV + Directional) ===
        // Sell 2x OTM, Buy 1x closer — big credit, limited risk on one side
        if (ivRank >= 35) {
            scanRatioSpreads(results, quotes, underlying, expiry, dte, step, lotSize, atmStrike,
                spot, expectedMoveSteps, ivRank, regimeName, futPremium, txnCostBase);
        }

        // === STRATEGY 3: DYNAMIC STRANGLE (Sideways + Any IV) ===
        // Sell OTM put + call at expected move boundaries with wings for protection
        scanDynamicIronCondor(results, quotes, underlying, expiry, dte, step, lotSize, atmStrike,
            spot, expectedMoveSteps, ivRank, regimeName, txnCostBase);

        // === STRATEGY 4: SKEW EXPLOITER (When put/call skew is extreme) ===
        // Risk reversal with hedge — sell expensive skew side, buy cheap side
        if (putSkew > 1.4 || putSkew < 0.7) {
            scanSkewExploiter(results, quotes, underlying, expiry, dte, step, lotSize, atmStrike,
                spot, putSkew, ivRank, regimeName, txnCostBase);
        }

        // === STRATEGY 5: MOMENTUM LADDER (Strong trend detected) ===
        // Buy 1 ITM/ATM, Sell 1 OTM, Sell 1 far OTM — ride the trend with credit
        if ("TRENDING_UP".equals(regimeName) || "TRENDING_DOWN".equals(regimeName)) {
            scanMomentumLadder(results, quotes, underlying, expiry, dte, step, lotSize, atmStrike,
                spot, expectedMoveSteps, ivRank, regimeName, futPremium, txnCostBase);
        }

        // === STRATEGY 6: VOLATILITY CRUSH PLAY (Very High IV — pre-event) ===
        // Calendar-like: position to profit from IV compression
        if (ivRank >= 70) {
            scanVolCrushPlay(results, quotes, underlying, expiry, dte, step, lotSize, atmStrike,
                spot, expectedMoveSteps, ivRank, regimeName, txnCostBase);
        }

        return results;
    }

    // ═══════════════ IRON BUTTERFLY ═══════════════
    private void scanIronButterfly(List<Map<String, Object>> results,
            Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, long dte, int step, int lotSize, int atmStrike,
            double spot, int expectedMoveSteps, double ivRank, String regime, double txnCostBase) {

        // Dynamic wing width: wider in high IV (more premium to collect), tighter in low IV
        int minWing = Math.max(2, expectedMoveSteps - 1);
        int maxWing = Math.min(8, expectedMoveSteps + 2);

        for (int wingWidth = minWing; wingWidth <= maxWing; wingWidth++) {
            int putWingStrike = atmStrike - wingWidth * step;
            int callWingStrike = atmStrike + wingWidth * step;

            OptionChainService.OptionQuote sellCE = getQuote(quotes, underlying, expiry, atmStrike, "CE");
            OptionChainService.OptionQuote sellPE = getQuote(quotes, underlying, expiry, atmStrike, "PE");
            OptionChainService.OptionQuote buyPE = getQuote(quotes, underlying, expiry, putWingStrike, "PE");
            OptionChainService.OptionQuote buyCE = getQuote(quotes, underlying, expiry, callWingStrike, "CE");
            if (sellCE == null || sellPE == null || buyPE == null || buyCE == null) continue;

            double credit = sellCE.effectiveBid() + sellPE.effectiveBid() - buyPE.effectiveAsk() - buyCE.effectiveAsk();
            if (credit <= 0) continue;

            double wingWidthPts = wingWidth * step;
            double maxLoss = (wingWidthPts - credit) * lotSize;
            double txnCost = txnCostBase * 4 + 60;
            double creditRs = credit * lotSize;
            double netCreditRs = creditRs - txnCost;
            if (netCreditRs < 100 || maxLoss <= 0) continue;

            double riskReward = netCreditRs / (maxLoss + txnCost);
            double profitZonePct = credit / spot * 100;
            double winRate = Math.min(85, 40 + profitZonePct * 15);

            // Adaptive score: favors high IV, sideways, good R:R
            double score = computeAdaptiveScore("IRON_BUTTERFLY", ivRank, regime, riskReward, winRate, netCreditRs, dte);

            Map<String, Object> opp = buildBase(underlying, expiry, dte, lotSize, spot, ivRank, regime, score);
            opp.put("adaptiveType", "IRON_BUTTERFLY");
            opp.put("adaptiveReason", ivRank >= 60 ? "High IV → max theta harvest at ATM"
                : "Moderate IV → controlled premium capture");
            opp.put("sellCEStrike", atmStrike);
            opp.put("sellPEStrike", atmStrike);
            opp.put("buyCEStrike", callWingStrike);
            opp.put("buyPEStrike", putWingStrike);
            opp.put("wingWidth", wingWidth);
            opp.put("wingWidthPts", r2(wingWidthPts));
            opp.put("credit", r2(credit));
            opp.put("creditRs", r2(creditRs));
            opp.put("netCreditRs", r2(netCreditRs));
            opp.put("maxLoss", r2(maxLoss + txnCost));
            opp.put("maxProfit", r2(netCreditRs));
            opp.put("riskRewardRatio", r2(riskReward));
            opp.put("profitZone", String.format("%d — %d", putWingStrike, callWingStrike));
            opp.put("profitZonePct", r2(profitZonePct));
            opp.put("estimatedWinRate", r2(winRate));
            opp.put("breakEvenDown", r2(atmStrike - credit));
            opp.put("breakEvenUp", r2(atmStrike + credit));
            opp.put("scenarioFlat", r2(netCreditRs));
            opp.put("scenarioUp", r2(-maxLoss - txnCost));
            opp.put("scenarioDown", r2(-maxLoss - txnCost));
            opp.put("legList", List.of(
                makeLeg(quotes, underlying, expiry, atmStrike, "PE", "SELL", 1, sellPE.effectiveBid()),
                makeLeg(quotes, underlying, expiry, putWingStrike, "PE", "BUY", 1, buyPE.effectiveAsk()),
                makeLeg(quotes, underlying, expiry, atmStrike, "CE", "SELL", 1, sellCE.effectiveBid()),
                makeLeg(quotes, underlying, expiry, callWingStrike, "CE", "BUY", 1, buyCE.effectiveAsk())
            ));
            results.add(opp);
        }
    }

    // ═══════════════ RATIO SPREADS (Directional) ═══════════════
    private void scanRatioSpreads(List<Map<String, Object>> results,
            Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, long dte, int step, int lotSize, int atmStrike,
            double spot, int expectedMoveSteps, double ivRank, String regime, double futPremium, double txnCostBase) {

        // Put ratio spread (bullish bias): Buy 1 ATM/NTM put, Sell 2 OTM puts, Buy 1 far OTM put (hedge)
        // Call ratio spread (bearish bias): Buy 1 ATM/NTM call, Sell 2 OTM calls, Buy 1 far OTM call
        for (String direction : List.of("BULL", "BEAR")) {
            boolean isBull = "BULL".equals(direction);
            String optType = isBull ? "PE" : "CE";

            // Skip if regime contradicts direction
            if (isBull && "TRENDING_DOWN".equals(regime)) continue;
            if (!isBull && "TRENDING_UP".equals(regime)) continue;

            for (int bodyDist = 2; bodyDist <= Math.min(5, expectedMoveSteps); bodyDist++) {
                int buyStrike = atmStrike; // ATM
                int sellStrike = isBull ? atmStrike - bodyDist * step : atmStrike + bodyDist * step;
                int hedgeStrike = isBull ? sellStrike - 3 * step : sellStrike + 3 * step;

                OptionChainService.OptionQuote buyQ = getQuote(quotes, underlying, expiry, buyStrike, optType);
                OptionChainService.OptionQuote sellQ = getQuote(quotes, underlying, expiry, sellStrike, optType);
                OptionChainService.OptionQuote hedgeQ = getQuote(quotes, underlying, expiry, hedgeStrike, optType);
                if (buyQ == null || sellQ == null || hedgeQ == null) continue;

                // Buy 1, Sell 2, Buy 1 (hedge) = ratio butterfly with credit
                double cost = buyQ.effectiveAsk() + hedgeQ.effectiveAsk() - 2 * sellQ.effectiveBid();
                double credit = -cost; // positive if net credit
                if (credit <= 0) continue; // only take for credit

                double narrowWidth = Math.abs(buyStrike - sellStrike);
                double wideWidth = Math.abs(hedgeStrike - sellStrike);
                double maxProfit = (narrowWidth + credit) * lotSize;
                double maxLoss = (wideWidth - credit) * lotSize;
                double txnCost = txnCostBase * 4 + 60;
                double netCredit = credit * lotSize - txnCost;
                if (maxLoss <= 0 || maxProfit <= txnCost) continue;

                double riskReward = maxProfit / (maxLoss + txnCost);
                double winRate = Math.min(80, 45 + (credit / narrowWidth) * 40);
                double score = computeAdaptiveScore("RATIO_SPREAD", ivRank, regime, riskReward, winRate, netCredit, dte);

                String reason = isBull
                    ? String.format("Bullish bias — sell 2x OTM puts for %.0f%% credit of width", credit / narrowWidth * 100)
                    : String.format("Bearish bias — sell 2x OTM calls for %.0f%% credit of width", credit / narrowWidth * 100);

                Map<String, Object> opp = buildBase(underlying, expiry, dte, lotSize, spot, ivRank, regime, score);
                opp.put("adaptiveType", "RATIO_SPREAD");
                opp.put("adaptiveReason", reason);
                opp.put("direction", direction);
                opp.put("optionType", optType);
                opp.put("buyStrike", buyStrike);
                opp.put("sellStrike", sellStrike);
                opp.put("hedgeStrike", hedgeStrike);
                opp.put("credit", r2(credit));
                opp.put("creditRs", r2(credit * lotSize));
                opp.put("netCreditRs", r2(netCredit));
                opp.put("maxProfit", r2(maxProfit));
                opp.put("maxLoss", r2(maxLoss + txnCost));
                opp.put("riskRewardRatio", r2(riskReward));
                opp.put("estimatedWinRate", r2(winRate));
                opp.put("profitZone", isBull
                    ? String.format("%d and above", hedgeStrike)
                    : String.format("%d and below", hedgeStrike));
                opp.put("scenarioFlat", r2(netCredit));
                opp.put("scenarioUp", isBull ? r2(maxProfit - txnCost) : r2(-maxLoss - txnCost));
                opp.put("scenarioDown", isBull ? r2(-maxLoss - txnCost) : r2(maxProfit - txnCost));
                opp.put("legList", List.of(
                    makeLeg(quotes, underlying, expiry, buyStrike, optType, "BUY", 1, buyQ.effectiveAsk()),
                    makeLeg(quotes, underlying, expiry, sellStrike, optType, "SELL", 2, sellQ.effectiveBid()),
                    makeLeg(quotes, underlying, expiry, hedgeStrike, optType, "BUY", 1, hedgeQ.effectiveAsk())
                ));
                results.add(opp);
            }
        }
    }

    // ═══════════════ DYNAMIC IRON CONDOR (Expected Move Based) ═══════════════
    private void scanDynamicIronCondor(List<Map<String, Object>> results,
            Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, long dte, int step, int lotSize, int atmStrike,
            double spot, int expectedMoveSteps, double ivRank, String regime, double txnCostBase) {

        // Sell strikes at expected move boundary, buy wings 2-3 steps beyond
        // Dynamic: wing distance expands in high IV, contracts in low IV
        for (int sellDist = expectedMoveSteps - 1; sellDist <= expectedMoveSteps + 2; sellDist++) {
            if (sellDist < 2) continue;
            for (int wingWidth = 2; wingWidth <= 4; wingWidth++) {
                int putSellStrike = atmStrike - sellDist * step;
                int callSellStrike = atmStrike + sellDist * step;
                int putBuyStrike = putSellStrike - wingWidth * step;
                int callBuyStrike = callSellStrike + wingWidth * step;

                // Apply directional bias: shift strikes toward the expected direction
                if ("TRENDING_UP".equals(regime)) {
                    // Widen call side, tighten put side — bullish bias
                    callSellStrike += step;
                    callBuyStrike += step;
                } else if ("TRENDING_DOWN".equals(regime)) {
                    putSellStrike -= step;
                    putBuyStrike -= step;
                }

                OptionChainService.OptionQuote pSell = getQuote(quotes, underlying, expiry, putSellStrike, "PE");
                OptionChainService.OptionQuote pBuy = getQuote(quotes, underlying, expiry, putBuyStrike, "PE");
                OptionChainService.OptionQuote cSell = getQuote(quotes, underlying, expiry, callSellStrike, "CE");
                OptionChainService.OptionQuote cBuy = getQuote(quotes, underlying, expiry, callBuyStrike, "CE");
                if (pSell == null || pBuy == null || cSell == null || cBuy == null) continue;

                double credit = pSell.effectiveBid() - pBuy.effectiveAsk() + cSell.effectiveBid() - cBuy.effectiveAsk();
                if (credit <= 0) continue;

                double putWidth = putSellStrike - putBuyStrike;
                double callWidth = callBuyStrike - callSellStrike;
                double maxLossPut = (putWidth - credit) * lotSize;
                double maxLossCall = (callWidth - credit) * lotSize;
                double maxLoss = Math.max(maxLossPut, maxLossCall);
                double txnCost = txnCostBase * 4 + 60;
                double creditRs = credit * lotSize;
                double netCreditRs = creditRs - txnCost;
                if (netCreditRs < 50 || maxLoss <= 0) continue;

                double profitZoneWidth = callSellStrike - putSellStrike;
                double profitZonePct = profitZoneWidth / spot * 100;
                double riskReward = netCreditRs / (maxLoss + txnCost);
                double winRate = Math.min(88, 45 + profitZonePct * 4);
                double score = computeAdaptiveScore("DYNAMIC_CONDOR", ivRank, regime, riskReward, winRate, netCreditRs, dte);

                String reason = String.format("Expected move: ±%.0f pts (%.1f%%) → strikes at boundary",
                    spot * profitZonePct / 200, profitZonePct / 2);

                Map<String, Object> opp = buildBase(underlying, expiry, dte, lotSize, spot, ivRank, regime, score);
                opp.put("adaptiveType", "DYNAMIC_CONDOR");
                opp.put("adaptiveReason", reason);
                opp.put("putSellStrike", putSellStrike);
                opp.put("putBuyStrike", putBuyStrike);
                opp.put("callSellStrike", callSellStrike);
                opp.put("callBuyStrike", callBuyStrike);
                opp.put("credit", r2(credit));
                opp.put("creditRs", r2(creditRs));
                opp.put("netCreditRs", r2(netCreditRs));
                opp.put("maxLoss", r2(maxLoss + txnCost));
                opp.put("maxProfit", r2(netCreditRs));
                opp.put("riskRewardRatio", r2(riskReward));
                opp.put("profitZone", String.format("%d — %d", putSellStrike, callSellStrike));
                opp.put("profitZonePct", r2(profitZonePct));
                opp.put("estimatedWinRate", r2(winRate));
                opp.put("directionalBias", "TRENDING_UP".equals(regime) ? "BULLISH"
                    : "TRENDING_DOWN".equals(regime) ? "BEARISH" : "NEUTRAL");
                opp.put("scenarioFlat", r2(netCreditRs));
                opp.put("scenarioUp", r2(-maxLossCall - txnCost));
                opp.put("scenarioDown", r2(-maxLossPut - txnCost));
                opp.put("legList", List.of(
                    makeLeg(quotes, underlying, expiry, putSellStrike, "PE", "SELL", 1, pSell.effectiveBid()),
                    makeLeg(quotes, underlying, expiry, putBuyStrike, "PE", "BUY", 1, pBuy.effectiveAsk()),
                    makeLeg(quotes, underlying, expiry, callSellStrike, "CE", "SELL", 1, cSell.effectiveBid()),
                    makeLeg(quotes, underlying, expiry, callBuyStrike, "CE", "BUY", 1, cBuy.effectiveAsk())
                ));
                results.add(opp);
            }
        }
    }

    // ═══════════════ SKEW EXPLOITER ═══════════════
    private void scanSkewExploiter(List<Map<String, Object>> results,
            Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, long dte, int step, int lotSize, int atmStrike,
            double spot, double putSkew, double ivRank, String regime, double txnCostBase) {

        // When puts are expensive (putSkew > 1.4): sell put spread, buy call spread (risk reversal with hedges)
        // When calls are expensive (putSkew < 0.7): sell call spread, buy put spread
        boolean putsExpensive = putSkew > 1.4;

        for (int dist = 2; dist <= 5; dist++) {
            int sellStrike, sellHedge, buyStrike, buyHedge;
            String sellType, buyType;

            if (putsExpensive) {
                sellType = "PE"; buyType = "CE";
                sellStrike = atmStrike - dist * step;
                sellHedge = sellStrike - 3 * step;
                buyStrike = atmStrike + (dist - 1) * step;
                buyHedge = buyStrike + 3 * step;
            } else {
                sellType = "CE"; buyType = "PE";
                sellStrike = atmStrike + dist * step;
                sellHedge = sellStrike + 3 * step;
                buyStrike = atmStrike - (dist - 1) * step;
                buyHedge = buyStrike - 3 * step;
            }

            OptionChainService.OptionQuote sellQ = getQuote(quotes, underlying, expiry, sellStrike, sellType);
            OptionChainService.OptionQuote sellHedgeQ = getQuote(quotes, underlying, expiry, sellHedge, sellType);
            OptionChainService.OptionQuote buyQ = getQuote(quotes, underlying, expiry, buyStrike, buyType);
            OptionChainService.OptionQuote buyHedgeQ = getQuote(quotes, underlying, expiry, buyHedge, buyType);
            if (sellQ == null || sellHedgeQ == null || buyQ == null || buyHedgeQ == null) continue;

            double sellCredit = sellQ.effectiveBid() - sellHedgeQ.effectiveAsk();
            double buyCost = buyQ.effectiveAsk() - buyHedgeQ.effectiveBid();
            double netCredit = sellCredit - buyCost;
            if (netCredit <= 0) continue; // must be net credit

            double sellWidth = Math.abs(sellStrike - sellHedge);
            double buyWidth = Math.abs(buyStrike - buyHedge);
            double maxLossSell = (sellWidth - sellCredit) * lotSize;
            double maxProfitBuy = (buyWidth - buyCost) * lotSize;
            double txnCost = txnCostBase * 4 + 60;
            double netCreditRs = netCredit * lotSize - txnCost;
            if (netCreditRs < 30) continue;

            double maxLoss = maxLossSell; // defined risk on sell side
            double riskReward = (netCreditRs + maxProfitBuy) / (maxLoss + txnCost);
            double winRate = Math.min(75, 45 + (putSkew > 1.4 ? putSkew - 1 : 1 / putSkew - 1) * 20);
            double skewEdge = Math.abs(putSkew - 1.0) * 100;
            double score = computeAdaptiveScore("SKEW_EXPLOITER", ivRank, regime, riskReward, winRate, netCreditRs, dte);

            String reason = putsExpensive
                ? String.format("Put skew %.1fx → sell expensive puts, buy cheap calls", putSkew)
                : String.format("Call skew %.1fx → sell expensive calls, buy cheap puts", 1 / putSkew);

            Map<String, Object> opp = buildBase(underlying, expiry, dte, lotSize, spot, ivRank, regime, score);
            opp.put("adaptiveType", "SKEW_EXPLOITER");
            opp.put("adaptiveReason", reason);
            opp.put("skewRatio", r2(putSkew));
            opp.put("skewEdge", r2(skewEdge));
            opp.put("sellStrike", sellStrike);
            opp.put("sellHedgeStrike", sellHedge);
            opp.put("buyStrike", buyStrike);
            opp.put("buyHedgeStrike", buyHedge);
            opp.put("sellType", sellType);
            opp.put("buyType", buyType);
            opp.put("credit", r2(netCredit));
            opp.put("creditRs", r2(netCredit * lotSize));
            opp.put("netCreditRs", r2(netCreditRs));
            opp.put("maxLoss", r2(maxLoss + txnCost));
            opp.put("maxProfit", r2(netCreditRs + maxProfitBuy));
            opp.put("riskRewardRatio", r2(riskReward));
            opp.put("estimatedWinRate", r2(winRate));
            opp.put("scenarioFlat", r2(netCreditRs));
            opp.put("scenarioUp", putsExpensive ? r2(netCreditRs + maxProfitBuy) : r2(-maxLoss - txnCost));
            opp.put("scenarioDown", putsExpensive ? r2(-maxLoss - txnCost) : r2(netCreditRs + maxProfitBuy));
            opp.put("legList", List.of(
                makeLeg(quotes, underlying, expiry, sellStrike, sellType, "SELL", 1, sellQ.effectiveBid()),
                makeLeg(quotes, underlying, expiry, sellHedge, sellType, "BUY", 1, sellHedgeQ.effectiveAsk()),
                makeLeg(quotes, underlying, expiry, buyStrike, buyType, "BUY", 1, buyQ.effectiveAsk()),
                makeLeg(quotes, underlying, expiry, buyHedge, buyType, "SELL", 1, buyHedgeQ.effectiveBid())
            ));
            results.add(opp);
        }
    }

    // ═══════════════ MOMENTUM LADDER ═══════════════
    private void scanMomentumLadder(List<Map<String, Object>> results,
            Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, long dte, int step, int lotSize, int atmStrike,
            double spot, int expectedMoveSteps, double ivRank, String regime, double futPremium, double txnCostBase) {

        boolean bullish = "TRENDING_UP".equals(regime);
        String optType = bullish ? "CE" : "PE";

        // Buy 1 ATM, Sell 1 OTM, Sell 1 far OTM (1x2 ratio with extra hedge at far end)
        for (int otmDist = 2; otmDist <= 4; otmDist++) {
            for (int farDist = otmDist + 2; farDist <= otmDist + 5; farDist++) {
                int buyStrike = atmStrike;
                int sellStrike1 = bullish ? atmStrike + otmDist * step : atmStrike - otmDist * step;
                int sellStrike2 = bullish ? atmStrike + farDist * step : atmStrike - farDist * step;
                // Hedge: buy 1 at far end to cap risk
                int hedgeStrike = bullish ? sellStrike2 + 2 * step : sellStrike2 - 2 * step;

                OptionChainService.OptionQuote buyQ = getQuote(quotes, underlying, expiry, buyStrike, optType);
                OptionChainService.OptionQuote sell1Q = getQuote(quotes, underlying, expiry, sellStrike1, optType);
                OptionChainService.OptionQuote sell2Q = getQuote(quotes, underlying, expiry, sellStrike2, optType);
                OptionChainService.OptionQuote hedgeQ = getQuote(quotes, underlying, expiry, hedgeStrike, optType);
                if (buyQ == null || sell1Q == null || sell2Q == null || hedgeQ == null) continue;

                double debit = buyQ.effectiveAsk() + hedgeQ.effectiveAsk() - sell1Q.effectiveBid() - sell2Q.effectiveBid();
                double maxProfit = Math.abs(sellStrike1 - buyStrike) * lotSize; // profit at first sell strike
                double maxLoss = Math.max(debit * lotSize, Math.abs(hedgeStrike - sellStrike2) * lotSize);
                double txnCost = txnCostBase * 4 + 60;

                if (debit > maxProfit / lotSize || maxLoss <= 0) continue;
                double netDebit = debit * lotSize + txnCost;
                if (netDebit > maxProfit * 0.7) continue; // only if debit < 70% of max profit

                double riskReward = maxProfit / (netDebit + txnCost);
                double winRate = Math.min(70, 35 + Math.abs(futPremium) * 15);
                double score = computeAdaptiveScore("MOMENTUM_LADDER", ivRank, regime, riskReward, winRate, maxProfit - netDebit, dte);

                String reason = String.format("%s momentum — ladder profits from continued %s move",
                    bullish ? "Bullish" : "Bearish", bullish ? "upward" : "downward");

                Map<String, Object> opp = buildBase(underlying, expiry, dte, lotSize, spot, ivRank, regime, score);
                opp.put("adaptiveType", "MOMENTUM_LADDER");
                opp.put("adaptiveReason", reason);
                opp.put("direction", bullish ? "BULL" : "BEAR");
                opp.put("optionType", optType);
                opp.put("buyStrike", buyStrike);
                opp.put("sellStrike1", sellStrike1);
                opp.put("sellStrike2", sellStrike2);
                opp.put("hedgeStrike", hedgeStrike);
                opp.put("debit", r2(debit));
                opp.put("debitRs", r2(debit * lotSize));
                opp.put("netCreditRs", r2(-netDebit)); // negative = debit
                opp.put("maxProfit", r2(maxProfit));
                opp.put("maxLoss", r2(maxLoss + txnCost));
                opp.put("riskRewardRatio", r2(riskReward));
                opp.put("creditRs", r2(-netDebit));
                opp.put("estimatedWinRate", r2(winRate));
                opp.put("profitZone", bullish
                    ? String.format("%d — %d", sellStrike1, sellStrike2)
                    : String.format("%d — %d", sellStrike2, sellStrike1));
                opp.put("scenarioFlat", r2(-netDebit)); // lose debit if flat
                opp.put("scenarioUp", bullish ? r2(maxProfit - netDebit) : r2(-maxLoss - txnCost));
                opp.put("scenarioDown", bullish ? r2(-maxLoss - txnCost) : r2(maxProfit - netDebit));
                opp.put("legList", List.of(
                    makeLeg(quotes, underlying, expiry, buyStrike, optType, "BUY", 1, buyQ.effectiveAsk()),
                    makeLeg(quotes, underlying, expiry, sellStrike1, optType, "SELL", 1, sell1Q.effectiveBid()),
                    makeLeg(quotes, underlying, expiry, sellStrike2, optType, "SELL", 1, sell2Q.effectiveBid()),
                    makeLeg(quotes, underlying, expiry, hedgeStrike, optType, "BUY", 1, hedgeQ.effectiveAsk())
                ));
                results.add(opp);
            }
        }
    }

    // ═══════════════ VOLATILITY CRUSH PLAY ═══════════════
    private void scanVolCrushPlay(List<Map<String, Object>> results,
            Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, long dte, int step, int lotSize, int atmStrike,
            double spot, int expectedMoveSteps, double ivRank, String regime, double txnCostBase) {

        // Short straddle with tight wings — aggressive theta play in very high IV
        // Tighter wings than iron butterfly because we expect IV to crush
        for (int wingWidth = 1; wingWidth <= 3; wingWidth++) {
            int putWing = atmStrike - wingWidth * step;
            int callWing = atmStrike + wingWidth * step;
            int farPutWing = putWing - 2 * step;
            int farCallWing = callWing + 2 * step;

            OptionChainService.OptionQuote sellCE = getQuote(quotes, underlying, expiry, callWing, "CE");
            OptionChainService.OptionQuote sellPE = getQuote(quotes, underlying, expiry, putWing, "PE");
            OptionChainService.OptionQuote buyCE = getQuote(quotes, underlying, expiry, farCallWing, "CE");
            OptionChainService.OptionQuote buyPE = getQuote(quotes, underlying, expiry, farPutWing, "PE");
            if (sellCE == null || sellPE == null || buyCE == null || buyPE == null) continue;

            double credit = sellCE.effectiveBid() + sellPE.effectiveBid() - buyCE.effectiveAsk() - buyPE.effectiveAsk();
            if (credit <= 0) continue;

            double hedgeWidth = 2 * step;
            double maxLoss = (hedgeWidth - credit) * lotSize;
            double txnCost = txnCostBase * 4 + 60;
            double creditRs = credit * lotSize;
            double netCreditRs = creditRs - txnCost;
            if (netCreditRs < 50 || maxLoss <= 0) continue;

            double riskReward = netCreditRs / (maxLoss + txnCost);
            double ivCrushBenefit = (ivRank - 50) * 0.5; // higher IV → more benefit from crush
            double winRate = Math.min(80, 50 + ivCrushBenefit);
            double score = computeAdaptiveScore("VOL_CRUSH", ivRank, regime, riskReward, winRate, netCreditRs, dte);

            Map<String, Object> opp = buildBase(underlying, expiry, dte, lotSize, spot, ivRank, regime, score);
            opp.put("adaptiveType", "VOL_CRUSH");
            opp.put("adaptiveReason", String.format("IV Rank %.0f%% → sell premium, profit from volatility compression", ivRank));
            opp.put("sellCEStrike", callWing);
            opp.put("sellPEStrike", putWing);
            opp.put("buyCEStrike", farCallWing);
            opp.put("buyPEStrike", farPutWing);
            opp.put("credit", r2(credit));
            opp.put("creditRs", r2(creditRs));
            opp.put("netCreditRs", r2(netCreditRs));
            opp.put("maxLoss", r2(maxLoss + txnCost));
            opp.put("maxProfit", r2(netCreditRs));
            opp.put("riskRewardRatio", r2(riskReward));
            opp.put("estimatedWinRate", r2(winRate));
            opp.put("profitZone", String.format("%d — %d", putWing, callWing));
            opp.put("ivCrushTarget", r2(ivRank * 0.6)); // target IV after crush
            opp.put("scenarioFlat", r2(netCreditRs));
            opp.put("scenarioUp", r2(-maxLoss - txnCost));
            opp.put("scenarioDown", r2(-maxLoss - txnCost));
            opp.put("legList", List.of(
                makeLeg(quotes, underlying, expiry, putWing, "PE", "SELL", 1, sellPE.effectiveBid()),
                makeLeg(quotes, underlying, expiry, farPutWing, "PE", "BUY", 1, buyPE.effectiveAsk()),
                makeLeg(quotes, underlying, expiry, callWing, "CE", "SELL", 1, sellCE.effectiveBid()),
                makeLeg(quotes, underlying, expiry, farCallWing, "CE", "BUY", 1, buyCE.effectiveAsk())
            ));
            results.add(opp);
        }
    }

    // ═══════════════ SCORING ENGINE ═══════════════
    private double computeAdaptiveScore(String type, double ivRank, String regime, double rr, double winRate, double netPnl, long dte) {
        double score = 0;

        // Base: risk-reward (0-30 points)
        score += Math.min(30, rr * 15);

        // Win rate component (0-25 points)
        score += Math.min(25, (winRate - 40) * 0.6);

        // IV alignment (0-20 points) — premium selling in high IV, buying in low IV
        boolean isSeller = Set.of("IRON_BUTTERFLY", "DYNAMIC_CONDOR", "VOL_CRUSH", "RATIO_SPREAD", "SKEW_EXPLOITER").contains(type);
        if (isSeller && ivRank > 50) score += Math.min(20, (ivRank - 50) * 0.4);
        if (!isSeller && ivRank < 40) score += Math.min(20, (40 - ivRank) * 0.5);

        // Regime alignment (0-15 points)
        switch (type) {
            case "IRON_BUTTERFLY", "VOL_CRUSH" -> { if ("SIDEWAYS".equals(regime) || "HIGH_VOLATILE".equals(regime)) score += 15; }
            case "MOMENTUM_LADDER" -> { if ("TRENDING_UP".equals(regime) || "TRENDING_DOWN".equals(regime)) score += 15; }
            case "SKEW_EXPLOITER" -> score += 10; // always somewhat applicable if skew exists
            case "DYNAMIC_CONDOR" -> { if ("SIDEWAYS".equals(regime)) score += 15; else score += 8; }
            case "RATIO_SPREAD" -> score += 10;
        }

        // DTE penalty — avoid very short or very long DTE
        if (dte >= 3 && dte <= 10) score += 10;
        else if (dte >= 1 && dte <= 15) score += 5;

        // Absolute profit size (small bonus, 0-5)
        if (netPnl > 500) score += 5;
        else if (netPnl > 200) score += 3;

        return r2(Math.min(100, Math.max(0, score)));
    }

    // ═══════════════ HELPERS ═══════════════
    private Map<String, Object> buildBase(String underlying, LocalDate expiry, long dte,
            int lotSize, double spot, double ivRank, String regime, double score) {
        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("strategyType", "ADAPTIVE");
        opp.put("underlying", underlying);
        opp.put("expiry", expiry.toString());
        opp.put("expiryDate", expiry.toString());
        opp.put("dte", dte);
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", r2(spot));
        opp.put("ivRank", r2(ivRank));
        opp.put("regime", regime);
        opp.put("adaptiveScore", score);
        return opp;
    }

    private Map<String, Object> makeLeg(Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, int strike, String optType, String side, int qty, double price) {
        return Map.of(
            "strike", strike, "optionType", optType, "side", side, "qty", qty, "price", price,
            "symbol", getSymbol(quotes, underlying, expiry, strike, optType)
        );
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

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
