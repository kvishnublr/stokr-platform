package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CalendarSpreadService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSpreadService.class);
    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_EDGE_RS = 300.0;

    private final OptionChainService optionChainService;

    public CalendarSpreadService(OptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    public List<Map<String, Object>> scanCalendarSpreads(String underlying, double spotPrice, double futuresPrice) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            if (spotPrice <= 0) return results;

            LocalDate nearExpiry = optionChainService.getWeeklyExpiryDate(underlying);
            LocalDate farExpiry = optionChainService.getMonthlyExpiry();
            if (nearExpiry == null || farExpiry == null || !farExpiry.isAfter(nearExpiry)) return results;

            int atmStrike = optionChainService.getATMStrike(underlying, spotPrice);
            List<Integer> strikes = optionChainService.generateStrikes(atmStrike, underlying);
            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.add(optionChainService.buildNfoSymbol(underlying, nearExpiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, nearExpiry, strike, "PE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, farExpiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, farExpiry, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            int lotSize = OptionChainService.getLotSize(underlying);

            for (int strike : strikes) {
                addSpread(results, quotes, underlying, nearExpiry, farExpiry, strike, "CE", spotPrice, futuresPrice, lotSize);
                addSpread(results, quotes, underlying, nearExpiry, farExpiry, strike, "PE", spotPrice, futuresPrice, lotSize);
            }

            results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("edgeAfterCosts", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("edgeAfterCosts", 0.0)).doubleValue()));
        } catch (Exception e) {
            log.error("Calendar spread scan failed for {}: {}", underlying, e.getMessage());
        }
        return results;
    }

    private void addSpread(List<Map<String, Object>> results,
                           Map<String, OptionChainService.OptionQuote> quotes,
                           String underlying,
                           LocalDate nearExpiry,
                           LocalDate farExpiry,
                           int strike,
                           String optionType,
                           double spotPrice,
                           double futuresPrice,
                           int lotSize) {
        String nearKey = optionChainService.buildNfoSymbol(underlying, nearExpiry, strike, optionType);
        String farKey = optionChainService.buildNfoSymbol(underlying, farExpiry, strike, optionType);
        OptionChainService.OptionQuote near = quotes.get(nearKey);
        OptionChainService.OptionQuote far = quotes.get(farKey);
        if (near == null || far == null) return;
        if (near.lastPrice <= 0 || far.lastPrice <= 0) return;

        double nearMid = mid(near);
        double farMid = mid(far);
        if (nearMid <= 0 || farMid <= 0) return;

        long nearDte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), nearExpiry.atStartOfDay()).toDays());
        long farDte = Math.max(nearDte + 1, Duration.between(LocalDate.now().atStartOfDay(), farExpiry.atStartOfDay()).toDays());
        double carry = Math.max(0.0, futuresPrice - spotPrice) * ((farDte - nearDte) / 365.0) * RISK_FREE_RATE;
        double spreadValue = farMid - nearMid;
        double expectedRange = Math.max(1.0, Math.abs(carry));
        double edgePoints = spreadValue - expectedRange;
        double edgeAfterCosts = edgePoints * lotSize - 40.0;
        if (Math.abs(edgeAfterCosts) < MIN_EDGE_RS) return;

        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("type", "CALENDAR_SPREAD");
        opp.put("underlying", underlying);
        opp.put("optionType", optionType);
        opp.put("strike", strike);
        opp.put("nearExpiry", nearExpiry.toString());
        opp.put("farExpiry", farExpiry.toString());
        opp.put("daysNear", nearDte);
        opp.put("daysFar", farDte);
        opp.put("nearSymbol", nearKey);
        opp.put("farSymbol", farKey);
        opp.put("nearPrice", round2(nearMid));
        opp.put("farPrice", round2(farMid));
        opp.put("spread", round2(spreadValue));
        opp.put("expectedCarry", round2(expectedRange));
        opp.put("edgePoints", round2(edgePoints));
        opp.put("edgeAfterCosts", round2(edgeAfterCosts));
        opp.put("action", edgeAfterCosts > 0 ? "SELL_FAR_BUY_NEAR" : "BUY_FAR_SELL_NEAR");
        opp.put("lotSize", lotSize);
        results.add(opp);
    }

    private double mid(OptionChainService.OptionQuote q) {
        if (q.bid > 0 && q.ask > 0) return (q.bid + q.ask) / 2.0;
        if (q.lastPrice > 0) return q.lastPrice;
        return 0.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
