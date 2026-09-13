package com.stokr.smartstrategy.morningtheta;

import com.stokr.arbitrage.ArbitrageCosts;
import com.stokr.arbitrage.FuturesKeyResolver;
import com.stokr.arbitrage.OptionChainService;
import com.stokr.arbitrage.ZerodhaSpotPriceFetcher;
import com.stokr.smartstrategy.morningtheta.MorningRangeTracker.DayType;
import com.stokr.smartstrategy.morningtheta.MorningRangeTracker.RangeData;
import com.stokr.smartstrategy.morningtheta.MorningRangeTracker.TrendDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
public class MorningRangeThetaScanner {

    private final MorningRangeTracker rangeTracker;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK"
    );

    public MorningRangeThetaScanner(MorningRangeTracker rangeTracker,
                                     OptionChainService optionChainService,
                                     ZerodhaSpotPriceFetcher spotFetcher) {
        this.rangeTracker = rangeTracker;
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        // Don't scan before range is frozen (10:15)
        if (now.isBefore(LocalTime.of(10, 15))) {
            return List.of();
        }
        // Don't scan after 2:30 PM — too late for new entries
        if (now.isAfter(LocalTime.of(14, 30))) {
            return List.of();
        }

        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try {
                List<Map<String, Object>> opps = scanForUnderlying(u);
                results.addAll(opps);
            } catch (Exception e) {
                log.error("Morning Range Theta scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("expectedPremiumRs", 0)).doubleValue(),
            ((Number) a.getOrDefault("expectedPremiumRs", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();

        RangeData range = rangeTracker.getRange(underlying);
        if (range == null || !range.frozen()) {
            return results;
        }

        // VIX override — skip if ATM IV too high
        if (range.atmIV() > 25) {
            log.info("MORNING_THETA: Skipping {} — ATM IV {}% too high", underlying, Math.round(range.atmIV() * 10.0) / 10.0);
            return results;
        }

        // Get current spot
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        LocalDate expiry = optionChainService.getNearestExpiry(underlying);
        long dte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays());
        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);

        switch (range.dayType()) {
            case QUIET -> results.addAll(buildIronCondor(underlying, spot, range, expiry, dte, step, lotSize));
            case NORMAL -> results.addAll(buildIronCondorWide(underlying, spot, range, expiry, dte, step, lotSize));
            case TRENDING -> results.addAll(buildCreditSpread(underlying, spot, range, expiry, dte, step, lotSize));
            case SPIKE -> results.addAll(buildSpikePlay(underlying, spot, range, expiry, dte, step, lotSize));
            default -> {}
        }

        return results;
    }

    private List<Map<String, Object>> buildIronCondor(String underlying, double spot,
            RangeData range, LocalDate expiry, long dte, int step, int lotSize) {
        // QUIET day: tight Iron Condor at range edges + 0.3% buffer
        double buffer = spot * 0.003;
        int ceSellStrike = roundStrike(range.high() + buffer, step, true);
        int peSellStrike = roundStrike(range.low() - buffer, step, false);
        int wingWidth = getWingWidth(underlying);
        int ceBuyStrike = ceSellStrike + wingWidth;
        int peBuyStrike = peSellStrike - wingWidth;

        return buildIronCondorOpp(underlying, spot, range, expiry, dte, lotSize,
            ceSellStrike, ceBuyStrike, peSellStrike, peBuyStrike, "QUIET — tight IC at range edges");
    }

    private List<Map<String, Object>> buildIronCondorWide(String underlying, double spot,
            RangeData range, LocalDate expiry, long dte, int step, int lotSize) {
        // NORMAL day: wider Iron Condor at range edges + 0.5% buffer
        double buffer = spot * 0.005;
        int ceSellStrike = roundStrike(range.high() + buffer, step, true);
        int peSellStrike = roundStrike(range.low() - buffer, step, false);
        int wingWidth = getWingWidth(underlying);
        int ceBuyStrike = ceSellStrike + wingWidth;
        int peBuyStrike = peSellStrike - wingWidth;

        return buildIronCondorOpp(underlying, spot, range, expiry, dte, lotSize,
            ceSellStrike, ceBuyStrike, peSellStrike, peBuyStrike, "NORMAL — wide IC at range + buffer");
    }

    private List<Map<String, Object>> buildCreditSpread(String underlying, double spot,
            RangeData range, LocalDate expiry, long dte, int step, int lotSize) {
        // TRENDING day: one-sided credit spread opposite to trend
        List<Map<String, Object>> results = new ArrayList<>();
        double buffer = spot * 0.003;
        int wingWidth = getWingWidth(underlying);

        if (range.trend() == TrendDirection.BULLISH) {
            // Market trending up — sell PUT credit spread below the low
            int peSellStrike = roundStrike(range.low() - buffer, step, false);
            int peBuyStrike = peSellStrike - wingWidth;
            results.addAll(buildCreditSpreadOpp(underlying, spot, range, expiry, dte, lotSize,
                peSellStrike, peBuyStrike, "PE", "TRENDING BULLISH — sell puts below range"));
        } else if (range.trend() == TrendDirection.BEARISH) {
            // Market trending down — sell CALL credit spread above the high
            int ceSellStrike = roundStrike(range.high() + buffer, step, true);
            int ceBuyStrike = ceSellStrike + wingWidth;
            results.addAll(buildCreditSpreadOpp(underlying, spot, range, expiry, dte, lotSize,
                ceSellStrike, ceBuyStrike, "CE", "TRENDING BEARISH — sell calls above range"));
        } else {
            // Neutral trend in trending range — build wider IC
            return buildIronCondorWide(underlying, spot, range, expiry, dte, step, lotSize);
        }
        return results;
    }

    private List<Map<String, Object>> buildSpikePlay(String underlying, double spot,
            RangeData range, LocalDate expiry, long dte, int step, int lotSize) {
        // SPIKE day: sell credit spread FAR from the extreme, or skip
        List<Map<String, Object>> results = new ArrayList<>();

        if (range.atmIV() > 20) {
            // Too volatile, skip
            return results;
        }

        double farBuffer = spot * 0.008;
        int wingWidth = getWingWidth(underlying);

        if (range.trend() == TrendDirection.BULLISH) {
            int peSellStrike = roundStrike(range.low() - farBuffer, step, false);
            int peBuyStrike = peSellStrike - wingWidth;
            results.addAll(buildCreditSpreadOpp(underlying, spot, range, expiry, dte, lotSize,
                peSellStrike, peBuyStrike, "PE", "SPIKE UP — far put spread (move already happened)"));
        } else if (range.trend() == TrendDirection.BEARISH) {
            int ceSellStrike = roundStrike(range.high() + farBuffer, step, true);
            int ceBuyStrike = ceSellStrike + wingWidth;
            results.addAll(buildCreditSpreadOpp(underlying, spot, range, expiry, dte, lotSize,
                ceSellStrike, ceBuyStrike, "CE", "SPIKE DOWN — far call spread (move already happened)"));
        }
        return results;
    }

    private List<Map<String, Object>> buildIronCondorOpp(String underlying, double spot,
            RangeData range, LocalDate expiry, long dte, int lotSize,
            int ceSellStrike, int ceBuyStrike, int peSellStrike, int peBuyStrike, String rationale) {
        List<Map<String, Object>> results = new ArrayList<>();

        List<String> instruments = new ArrayList<>();
        instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, ceSellStrike, "CE"));
        instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, ceBuyStrike, "CE"));
        instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, peSellStrike, "PE"));
        instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, peBuyStrike, "PE"));
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        OptionChainService.OptionQuote ceSell = getQuote(quotes, underlying, expiry, ceSellStrike, "CE");
        OptionChainService.OptionQuote ceBuy = getQuote(quotes, underlying, expiry, ceBuyStrike, "CE");
        OptionChainService.OptionQuote peSell = getQuote(quotes, underlying, expiry, peSellStrike, "PE");
        OptionChainService.OptionQuote peBuy = getQuote(quotes, underlying, expiry, peBuyStrike, "PE");
        if (ceSell == null || ceBuy == null || peSell == null || peBuy == null) return results;

        double credit = (ceSell.bid + peSell.bid) - (ceBuy.ask + peBuy.ask);
        if (credit <= 0) return results;

        double creditRs = credit * lotSize;
        double wingWidth = ceBuyStrike - ceSellStrike;
        double maxLoss = (wingWidth - credit) * lotSize;
        double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 60;
        double netCredit = creditRs - txnCost;
        if (netCredit < 100) return results;

        double ceDistance = (ceSellStrike - spot) / spot * 100;
        double peDistance = (spot - peSellStrike) / spot * 100;
        double estimatedWinRate = Math.min(90, 55 + Math.min(ceDistance, peDistance) * 5);

        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("strategyType", "MORNING_RANGE_THETA");
        opp.put("subType", "IRON_CONDOR");
        opp.put("underlying", underlying);
        opp.put("expiry", expiry.toString());
        opp.put("expiryDate", expiry.toString());
        opp.put("dte", dte);
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", round2(spot));
        opp.put("dayType", range.dayType().name());
        opp.put("trend", range.trend().name());
        opp.put("morningRangeHigh", round2(range.high()));
        opp.put("morningRangeLow", round2(range.low()));
        opp.put("morningRangePct", range.rangePercent());
        opp.put("atmIV", round2(range.atmIV()));
        opp.put("rationale", rationale);
        opp.put("ceSellStrike", ceSellStrike);
        opp.put("ceBuyStrike", ceBuyStrike);
        opp.put("peSellStrike", peSellStrike);
        opp.put("peBuyStrike", peBuyStrike);
        opp.put("credit", round2(credit));
        opp.put("creditRs", round2(creditRs));
        opp.put("netCreditRs", round2(netCredit));
        opp.put("maxLoss", round2(maxLoss));
        opp.put("maxProfit", round2(netCredit));
        opp.put("ceDistancePct", round2(ceDistance));
        opp.put("peDistancePct", round2(peDistance));
        opp.put("estimatedWinRate", round2(estimatedWinRate));
        opp.put("riskRewardRatio", round2(netCredit / maxLoss));
        opp.put("expectedPremiumRs", round2(netCredit));
        opp.put("edgeAfterCosts", round2(netCredit));
        opp.put("edgePoints", round2(credit));

        opp.put("action", String.format("SELL %dCE @ %.1f | BUY %dCE @ %.1f | SELL %dPE @ %.1f | BUY %dPE @ %.1f",
            ceSellStrike, ceSell.bid, ceBuyStrike, ceBuy.ask, peSellStrike, peSell.bid, peBuyStrike, peBuy.ask));

        opp.put("legList", List.of(
            buildLeg(ceSellStrike, "CE", "SELL", ceSell.bid, quotes, underlying, expiry),
            buildLeg(ceBuyStrike, "CE", "BUY", ceBuy.ask, quotes, underlying, expiry),
            buildLeg(peSellStrike, "PE", "SELL", peSell.bid, quotes, underlying, expiry),
            buildLeg(peBuyStrike, "PE", "BUY", peBuy.ask, quotes, underlying, expiry)
        ));

        // Exit parameters — intraday only
        opp.put("slPct", 100.0);
        opp.put("targetPct", 50.0);
        opp.put("timeExitMinutes", 45);

        results.add(opp);
        return results;
    }

    private List<Map<String, Object>> buildCreditSpreadOpp(String underlying, double spot,
            RangeData range, LocalDate expiry, long dte, int lotSize,
            int sellStrike, int buyStrike, String optType, String rationale) {
        List<Map<String, Object>> results = new ArrayList<>();

        List<String> instruments = new ArrayList<>();
        instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, sellStrike, optType));
        instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, buyStrike, optType));
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        OptionChainService.OptionQuote sellQ = getQuote(quotes, underlying, expiry, sellStrike, optType);
        OptionChainService.OptionQuote buyQ = getQuote(quotes, underlying, expiry, buyStrike, optType);
        if (sellQ == null || buyQ == null) return results;

        double credit = sellQ.bid - buyQ.ask;
        if (credit <= 0) return results;

        double creditRs = credit * lotSize;
        double wingWidth = Math.abs(buyStrike - sellStrike);
        double maxLoss = (wingWidth - credit) * lotSize;
        double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 2 + 30;
        double netCredit = creditRs - txnCost;
        if (netCredit < 50) return results;

        double distance = Math.abs(spot - sellStrike) / spot * 100;
        double estimatedWinRate = Math.min(92, 60 + distance * 5);

        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("strategyType", "MORNING_RANGE_THETA");
        opp.put("subType", "CREDIT_SPREAD");
        opp.put("spreadType", optType.equals("PE") ? "BULL_PUT" : "BEAR_CALL");
        opp.put("underlying", underlying);
        opp.put("expiry", expiry.toString());
        opp.put("expiryDate", expiry.toString());
        opp.put("dte", dte);
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", round2(spot));
        opp.put("dayType", range.dayType().name());
        opp.put("trend", range.trend().name());
        opp.put("morningRangeHigh", round2(range.high()));
        opp.put("morningRangeLow", round2(range.low()));
        opp.put("morningRangePct", range.rangePercent());
        opp.put("atmIV", round2(range.atmIV()));
        opp.put("rationale", rationale);
        opp.put("sellStrike", sellStrike);
        opp.put("buyStrike", buyStrike);
        opp.put("optionType", optType);
        opp.put("credit", round2(credit));
        opp.put("creditRs", round2(creditRs));
        opp.put("netCreditRs", round2(netCredit));
        opp.put("maxLoss", round2(maxLoss));
        opp.put("maxProfit", round2(netCredit));
        opp.put("distancePct", round2(distance));
        opp.put("estimatedWinRate", round2(estimatedWinRate));
        opp.put("riskRewardRatio", round2(netCredit / maxLoss));
        opp.put("expectedPremiumRs", round2(netCredit));
        opp.put("edgeAfterCosts", round2(netCredit));
        opp.put("edgePoints", round2(credit));

        String side1 = "PE".equals(optType) ? "SELL" : "SELL";
        String side2 = "PE".equals(optType) ? "BUY" : "BUY";
        opp.put("action", String.format("SELL %d%s @ %.1f | BUY %d%s @ %.1f",
            sellStrike, optType, sellQ.bid, buyStrike, optType, buyQ.ask));

        opp.put("legList", List.of(
            buildLeg(sellStrike, optType, "SELL", sellQ.bid, quotes, underlying, expiry),
            buildLeg(buyStrike, optType, "BUY", buyQ.ask, quotes, underlying, expiry)
        ));

        opp.put("slPct", 100.0);
        opp.put("targetPct", 50.0);
        opp.put("timeExitMinutes", 45);

        results.add(opp);
        return results;
    }

    private Map<String, Object> buildLeg(int strike, String optType, String side, double price,
            Map<String, OptionChainService.OptionQuote> quotes, String underlying, LocalDate expiry) {
        String symbol = getSymbol(quotes, underlying, expiry, strike, optType);
        return Map.of("strike", strike, "optionType", optType, "side", side,
            "qty", 1, "price", price, "symbol", symbol);
    }

    private int getWingWidth(String underlying) {
        return switch (underlying.toUpperCase()) {
            case "BANKNIFTY" -> 200;
            case "NIFTY" -> 100;
            default -> 100;
        };
    }

    private int roundStrike(double price, int step, boolean roundUp) {
        if (roundUp) return (int) (Math.ceil(price / step) * step);
        return (int) (Math.floor(price / step) * step);
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
