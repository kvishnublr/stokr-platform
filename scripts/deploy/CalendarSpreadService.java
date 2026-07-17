package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Calendar Spread Arbitrage: Detects term structure inversions between
 * NIFTY weekly and monthly options at the same strike.
 *
 * When weekly IV > monthly IV (inverted term structure), there's an opportunity
 * to sell the expensive weekly and buy the cheap monthly.
 */
@Service
public class CalendarSpreadService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSpreadService.class);
    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_SPREAD_EDGE = 200.0;

    private final OptionChainService optionChainService;

    public CalendarSpreadService(OptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    public List<Map<String, Object>> scanCalendarSpreads(String underlying, double spotPrice, double futuresPrice) {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            LocalDate weeklyExpiry = optionChainService.getWeeklyExpiry();
            LocalDate monthlyExpiry = optionChainService.getMonthlyExpiry();

            if (weeklyExpiry.equals(monthlyExpiry)) {
                log.info("Weekly and monthly expiry are the same ({}), skipping calendar spread", weeklyExpiry);
                return results;
            }

            double weeklyDTE = Duration.between(LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays();
            double monthlyDTE = Duration.between(LocalDate.now().atStartOfDay(), monthlyExpiry.atStartOfDay()).toDays();

            if (weeklyDTE <= 0 || monthlyDTE <= 0) {
                return results;
            }

            int atmStrike = getATMStrike(underlying, spotPrice);
            int step = "BANKNIFTY".equals(underlying) ? 100 : 50;
            int lotSize = OptionChainService.getLotSize(underlying);

            // Fetch weekly and monthly quotes for same strikes
            List<String> weeklyInstruments = new ArrayList<>();
            List<String> monthlyInstruments = new ArrayList<>();
            List<Integer> strikes = new ArrayList<>();

            for (int i = -5; i <= 5; i++) {
                int strike = atmStrike + i * step;
                strikes.add(strike);
                String weeklyCE = buildNfoSymbol(underlying, weeklyExpiry, strike, "CE");
                String weeklyPE = buildNfoSymbol(underlying, weeklyExpiry, strike, "PE");
                String monthlyCE = buildNfoSymbol(underlying, monthlyExpiry, strike, "CE");
                String monthlyPE = buildNfoSymbol(underlying, monthlyExpiry, strike, "PE");
                weeklyInstruments.addAll(List.of(weeklyCE, weeklyPE));
                monthlyInstruments.addAll(List.of(monthlyCE, monthlyPE));
            }

            // Fetch all quotes
            List<String> allInstruments = new ArrayList<>(weeklyInstruments);
            allInstruments.addAll(monthlyInstruments);

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(allInstruments);
            log.info("Calendar spread: fetched {} quotes for {} strikes", quotes.size(), strikes.size());

            for (int strike : strikes) {
                String weeklyCEKey = buildNfoSymbol(underlying, weeklyExpiry, strike, "CE");
                String weeklyPEKey = buildNfoSymbol(underlying, weeklyExpiry, strike, "PE");
                String monthlyCEKey = buildNfoSymbol(underlying, monthlyExpiry, strike, "CE");
                String monthlyPEKey = buildNfoSymbol(underlying, monthlyExpiry, strike, "PE");

                OptionChainService.OptionQuote wCE = quotes.get(weeklyCEKey);
                OptionChainService.OptionQuote wPE = quotes.get(weeklyPEKey);
                OptionChainService.OptionQuote mCE = quotes.get(monthlyCEKey);
                OptionChainService.OptionQuote mPE = quotes.get(monthlyPEKey);

                if (wCE == null || wPE == null || mCE == null || mPE == null) continue;
                if (wCE.lastPrice <= 0 || wPE.lastPrice <= 0 || mCE.lastPrice <= 0 || mPE.lastPrice <= 0) continue;

                // Calculate IV for each option
                double weeklyYears = weeklyDTE / 365.0;
                double monthlyYears = monthlyDTE / 365.0;

                double wCE_IV = BlackScholesCalculator.impliedVolatility(
                    wCE.lastPrice, spotPrice, strike, weeklyYears, RISK_FREE_RATE, true, 0.01, 100);
                double wPE_IV = BlackScholesCalculator.impliedVolatility(
                    wPE.lastPrice, spotPrice, strike, weeklyYears, RISK_FREE_RATE, false, 0.01, 100);
                double mCE_IV = BlackScholesCalculator.impliedVolatility(
                    mCE.lastPrice, spotPrice, strike, monthlyYears, RISK_FREE_RATE, true, 0.01, 100);
                double mPE_IV = BlackScholesCalculator.impliedVolatility(
                    mPE.lastPrice, spotPrice, strike, monthlyYears, RISK_FREE_RATE, false, 0.01, 100);

                double avgWeeklyIV = (wCE_IV + wPE_IV) / 2.0;
                double avgMonthlyIV = (mCE_IV + mPE_IV) / 2.0;

                if (avgWeeklyIV <= 0 || avgMonthlyIV <= 0) continue;

                // Term structure ratio: weekly/monthly IV
                double ivRatio = avgWeeklyIV / avgMonthlyIV;

                // Calendar spread value: monthly premium - weekly premium (for same-type options)
                double ceSpread = mCE.lastPrice - wCE.lastPrice;
                double peSpread = mPE.lastPrice - wPE.lastPrice;

                // Theoretical fair spread based on time value ratio
                double fairCESpread = wCE.lastPrice * (Math.sqrt(monthlyDTE / weeklyDTE) - 1);
                double fairPESpread = wPE.lastPrice * (Math.sqrt(monthlyDTE / weeklyDTE) - 1);

                // Detect inverted term structure (weekly IV > monthly IV)
                if (avgWeeklyIV > avgMonthlyIV * 1.05 && ivRatio > 1.05) {
                    // Edge: sell expensive weekly IV, buy cheap monthly IV
                    double ivDiff = (avgWeeklyIV - avgMonthlyIV) * 100;
                    double vegaPer1Pct = spotPrice * 0.004 * Math.sqrt(weeklyYears);
                    double expectedReversion = ivDiff * 0.30; // expect 30% reversion
                    double grossEdge = expectedReversion * vegaPer1Pct * lotSize;

                    // Costs: calendar spread is 4 legs
                    double avgPremium = (wCE.lastPrice + wPE.lastPrice + mCE.lastPrice + mPE.lastPrice) / 4;
                    double totalCosts = 4 * 20 + avgPremium * 0.001 * lotSize * 2; // brokerage + STT on sell legs

                    double netEdge = grossEdge - totalCosts;

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "CALENDAR_INVERSION");
                    result.put("underlying", underlying);
                    result.put("strike", strike);
                    result.put("action", "SELL_WEEKLY_BUY_MONTHLY");
                    result.put("weeklyExpiry", weeklyExpiry.toString());
                    result.put("monthlyExpiry", monthlyExpiry.toString());
                    result.put("weeklyDTE", weeklyDTE);
                    result.put("monthlyDTE", monthlyDTE);
                    result.put("spotPrice", spotPrice);
                    result.put("futuresPrice", futuresPrice);
                    result.put("weeklyCE", wCE.lastPrice);
                    result.put("weeklyPE", wPE.lastPrice);
                    result.put("monthlyCE", mCE.lastPrice);
                    result.put("monthlyPE", mPE.lastPrice);
                    result.put("avgWeeklyIV", Math.round(avgWeeklyIV * 10000.0) / 100.0);
                    result.put("avgMonthlyIV", Math.round(avgMonthlyIV * 10000.0) / 100.0);
                    result.put("ivRatio", Math.round(ivRatio * 100) / 100.0);
                    result.put("ivDiff", Math.round(ivDiff * 100) / 100.0);
                    result.put("ceSpread", Math.round(ceSpread * 100) / 100.0);
                    result.put("peSpread", Math.round(peSpread * 100) / 100.0);
                    result.put("edgeAfterCosts", Math.round(netEdge * 100) / 100.0);
                    result.put("confidence", Math.min(85, 60 + (int)(ivDiff)));
                    result.put("legs", String.format(
                        "SELL %d %s CE+PE | BUY %d %s CE+PE | IV diff %.1f%%",
                        strike, weeklyExpiry, strike, monthlyExpiry, ivDiff));
                    result.put("description", String.format(
                        "Calendar inversion: Weekly IV %.1f%% > Monthly IV %.1f%% (ratio %.2f). "
                        + "Sell expensive weekly, buy cheap monthly. Edge Rs.%.0f",
                        avgWeeklyIV * 100, avgMonthlyIV * 100, ivRatio, netEdge));

                    results.add(result);
                }

                // Also detect cheap calendar spread (monthly significantly cheaper than expected)
                if (ceSpread < fairCESpread * 0.5 && peSpread < fairPESpread * 0.5) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "CHEAP_CALENDAR");
                    result.put("underlying", underlying);
                    result.put("strike", strike);
                    result.put("action", "BUY_CALENDAR_SPREAD");
                    result.put("weeklyExpiry", weeklyExpiry.toString());
                    result.put("monthlyExpiry", monthlyExpiry.toString());
                    result.put("weeklyDTE", weeklyDTE);
                    result.put("monthlyDTE", monthlyDTE);
                    result.put("spotPrice", spotPrice);
                    result.put("futuresPrice", futuresPrice);
                    result.put("weeklyCE", wCE.lastPrice);
                    result.put("weeklyPE", wPE.lastPrice);
                    result.put("monthlyCE", mCE.lastPrice);
                    result.put("monthlyPE", mPE.lastPrice);
                    result.put("avgWeeklyIV", Math.round(avgWeeklyIV * 10000.0) / 100.0);
                    result.put("avgMonthlyIV", Math.round(avgMonthlyIV * 10000.0) / 100.0);
                    result.put("ceSpread", Math.round(ceSpread * 100) / 100.0);
                    result.put("peSpread", Math.round(peSpread * 100) / 100.0);
                    result.put("fairCESpread", Math.round(fairCESpread * 100) / 100.0);
                    result.put("edgeAfterCosts", Math.round((fairCESpread - ceSpread) * lotSize * 0.5));
                    result.put("confidence", 65);
                    result.put("legs", String.format(
                        "BUY %d %s CE+PE | SELL %d %s CE+PE",
                        strike, monthlyExpiry, strike, weeklyExpiry));
                    result.put("description", String.format(
                        "Cheap calendar: CE spread Rs.%.1f (fair %.1f), PE spread Rs.%.1f (fair %.1f)",
                        ceSpread, fairCESpread, peSpread, fairPESpread));

                    results.add(result);
                }
            }

            log.info("Calendar spread scan for {}: {} opportunities from {} strikes",
                underlying, results.size(), strikes.size());

        } catch (Exception e) {
            log.error("Error scanning calendar spreads for {}: {}", underlying, e.getMessage(), e);
        }

        return results;
    }

    private int getATMStrike(String underlying, double spotPrice) {
        return switch (underlying) {
            case "BANKNIFTY" -> (int) Math.round(spotPrice / 100.0) * 100;
            default -> (int) Math.round(spotPrice / 50.0) * 50;
        };
    }

    private String buildNfoSymbol(String underlying, LocalDate expiryDate, int strike, String type) {
        String clean = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        int month = expiryDate.getMonthValue();
        int day = expiryDate.getDayOfMonth();
        return String.format("%s%02d%d%02d%d%s", clean, yy, month, day, strike, type);
    }
}
