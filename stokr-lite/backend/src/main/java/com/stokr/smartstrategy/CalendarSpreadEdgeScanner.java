package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class CalendarSpreadEdgeScanner {

    private static final Logger log = LoggerFactory.getLogger(CalendarSpreadEdgeScanner.class);
    private static final double RISK_FREE_RATE = 0.065;

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public CalendarSpreadEdgeScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("Calendar spread scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("ivEdge", 0)).doubleValue(),
            ((Number) a.getOrDefault("ivEdge", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        LocalDate nearExpiry = optionChainService.getNearestExpiry(underlying);
        LocalDate farExpiry = optionChainService.getMonthlyExpiryDate(underlying);

        // If near and far are the same (e.g., monthly-only underlying on expiry week), try next month
        if (!farExpiry.isAfter(nearExpiry)) {
            farExpiry = getNextMonthExpiry(underlying, farExpiry);
        }
        if (!farExpiry.isAfter(nearExpiry)) return results;

        long nearDte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), nearExpiry.atStartOfDay()).toDays());
        long farDte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), farExpiry.atStartOfDay()).toDays());
        double nearYears = nearDte / 365.0;
        double farYears = farDte / 365.0;

        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        // Fetch quotes for both expiries
        List<String> instruments = new ArrayList<>();
        for (int i = -4; i <= 4; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, nearExpiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, nearExpiry, s, "PE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, farExpiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, farExpiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        // Calendar spread: Sell near-expiry, buy far-expiry (same strike, same type)
        for (String optType : List.of("CE", "PE")) {
            for (int offset = -2; offset <= 2; offset++) {
                int strike = atmStrike + offset * step;

                OptionChainService.OptionQuote nearQ = getQuote(quotes, underlying, nearExpiry, strike, optType);
                OptionChainService.OptionQuote farQ = getQuote(quotes, underlying, farExpiry, strike, optType);
                if (nearQ == null || farQ == null) continue;
                if (nearQ.bid <= 0 || farQ.ask <= 0) continue;

                double debit = farQ.ask - nearQ.bid;
                if (debit <= 0) continue;

                // Compute IVs
                boolean isCall = "CE".equals(optType);
                double nearIV = BlackScholesCalculator.impliedVolatility(nearQ.lastPrice, spot, strike, nearYears, RISK_FREE_RATE, isCall, 0.01, 50);
                double farIV = BlackScholesCalculator.impliedVolatility(farQ.lastPrice, spot, strike, farYears, RISK_FREE_RATE, isCall, 0.01, 50);

                double nearIVPct = nearIV > 0 ? nearIV * 100 : 0;
                double farIVPct = farIV > 0 ? farIV * 100 : 0;
                double ivEdge = nearIVPct - farIVPct;

                // Near-term theta decays faster — estimate daily theta differential
                double nearTheta = nearQ.lastPrice / nearDte;
                double farTheta = farQ.lastPrice / farDte;
                double dailyThetaEdge = nearTheta - farTheta;
                if (dailyThetaEdge <= 0) continue;

                double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 2 + 25;
                double maxLoss = debit * lotSize + txnCost;
                double expectedProfit = dailyThetaEdge * nearDte * lotSize * 0.6;

                // Only show if theta edge is meaningful
                if (dailyThetaEdge * lotSize < 10) continue;

                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("strategyType", "CALENDAR_SPREAD_EDGE");
                opp.put("underlying", underlying);
                opp.put("optionType", optType);
                opp.put("strike", strike);
                opp.put("nearExpiry", nearExpiry.toString());
                opp.put("farExpiry", farExpiry.toString());
                opp.put("expiry", nearExpiry.toString());
                opp.put("expiryDate", nearExpiry.toString());
                opp.put("nearDte", nearDte);
                opp.put("farDte", farDte);
                opp.put("dte", nearDte);
                opp.put("lotSize", lotSize);
                opp.put("spotPrice", round2(spot));
                opp.put("nearPrice", round2(nearQ.bid));
                opp.put("farPrice", round2(farQ.ask));
                opp.put("debit", round2(debit));
                opp.put("debitRs", round2(debit * lotSize));
                opp.put("nearIV", round2(nearIVPct));
                opp.put("farIV", round2(farIVPct));
                opp.put("ivEdge", round2(ivEdge));
                opp.put("dailyThetaEdge", round2(dailyThetaEdge));
                opp.put("dailyThetaEdgeRs", round2(dailyThetaEdge * lotSize));
                opp.put("expectedProfitRs", round2(expectedProfit - txnCost));
                opp.put("maxLoss", round2(maxLoss));
                opp.put("riskReward", maxLoss > 0 ? round2(expectedProfit / maxLoss) : 0);
                opp.put("riskLevel", "LOW");
                opp.put("ivBackwardation", ivEdge > 0);
                opp.put("nearSymbol", getSymbol(quotes, underlying, nearExpiry, strike, optType));
                opp.put("farSymbol", getSymbol(quotes, underlying, farExpiry, strike, optType));
                opp.put("action", String.format("SELL %s %d%s @ %.1f | BUY %s %d%s @ %.1f",
                    nearExpiry, strike, optType, nearQ.bid, farExpiry, strike, optType, farQ.ask));
                opp.put("legList", List.of(
                    Map.of("strike", strike, "optionType", optType, "side", "SELL", "qty", 1, "price", nearQ.bid,
                        "symbol", getSymbol(quotes, underlying, nearExpiry, strike, optType),
                        "expiry", nearExpiry.toString()),
                    Map.of("strike", strike, "optionType", optType, "side", "BUY", "qty", 1, "price", farQ.ask,
                        "symbol", getSymbol(quotes, underlying, farExpiry, strike, optType),
                        "expiry", farExpiry.toString())
                ));
                opp.put("edgePoints", round2(ivEdge));
                opp.put("edgeAfterCosts", round2(expectedProfit - txnCost));
                results.add(opp);
            }
        }
        return results;
    }

    private LocalDate getNextMonthExpiry(String underlying, LocalDate currentExpiry) {
        LocalDate nextMonth = currentExpiry.plusMonths(1);
        java.time.DayOfWeek targetDay = optionChainService.getExpiryDayForUnderlying(underlying);
        LocalDate lastDay = nextMonth.withDayOfMonth(nextMonth.lengthOfMonth());
        while (lastDay.getDayOfWeek() != targetDay) {
            lastDay = lastDay.minusDays(1);
        }
        return lastDay;
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
