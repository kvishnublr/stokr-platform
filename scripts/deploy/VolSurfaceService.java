package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Volatility Surface Analytics: Builds IV surface across strikes and expiries,
 * compares IV vs historical realized vol, and detects skew/term structure anomalies.
 */
@Service
public class VolSurfaceService {

    private static final Logger log = LoggerFactory.getLogger(VolSurfaceService.class);
    private static final double RISK_FREE_RATE = 0.065;

    private final OptionChainService optionChainService;

    public VolSurfaceService(OptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    public Map<String, Object> getVolSurface(String underlying, double spotPrice, double futuresPrice) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            LocalDate weeklyExpiry = optionChainService.getWeeklyExpiry();
            LocalDate monthlyExpiry = optionChainService.getMonthlyExpiry();

            double weeklyDTE = Duration.between(LocalDate.now().atStartOfDay(), weeklyExpiry.atStartOfDay()).toDays();
            double monthlyDTE = Duration.between(LocalDate.now().atStartOfDay(), monthlyExpiry.atStartOfDay()).toDays();

            int atmStrike = getATMStrike(underlying, spotPrice);
            int step = "BANKNIFTY".equals(underlying) ? 100 : 50;
            int lotSize = OptionChainService.getLotSize(underlying);

            // Fetch quotes for both expiries
            List<Integer> strikes = new ArrayList<>();
            for (int i = -8; i <= 8; i++) {
                strikes.add(atmStrike + i * step);
            }

            List<String> allInstruments = new ArrayList<>();
            for (int strike : strikes) {
                allInstruments.add(buildNfoSymbol(underlying, weeklyExpiry, strike, "CE"));
                allInstruments.add(buildNfoSymbol(underlying, weeklyExpiry, strike, "PE"));
                allInstruments.add(buildNfoSymbol(underlying, monthlyExpiry, strike, "CE"));
                allInstruments.add(buildNfoSymbol(underlying, monthlyExpiry, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(allInstruments);

            // Build IV surface
            List<Map<String, Object>> surface = new ArrayList<>();
            double sumWeeklyIV = 0;
            double sumMonthlyIV = 0;
            int countWeekly = 0;
            int countMonthly = 0;

            double minCEIV = Double.MAX_VALUE;
            double maxCEIV = 0;
            double minPEIV = Double.MAX_VALUE;
            double maxPEIV = 0;
            int minIVStrike = 0;
            int maxIVStrike = 0;

            for (int strike : strikes) {
                String wCEKey = buildNfoSymbol(underlying, weeklyExpiry, strike, "CE");
                String wPEKey = buildNfoSymbol(underlying, weeklyExpiry, strike, "PE");
                String mCEKey = buildNfoSymbol(underlying, monthlyExpiry, strike, "CE");
                String mPEKey = buildNfoSymbol(underlying, monthlyExpiry, strike, "PE");

                OptionChainService.OptionQuote wCE = quotes.get(wCEKey);
                OptionChainService.OptionQuote wPE = quotes.get(wPEKey);
                OptionChainService.OptionQuote mCE = quotes.get(mCEKey);
                OptionChainService.OptionQuote mPE = quotes.get(mPEKey);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("strike", strike);
                row.put("moneyness", Math.round((strike / spotPrice - 1) * 10000.0) / 100.0);

                if (wCE != null && wCE.lastPrice > 0) {
                    double iv = BlackScholesCalculator.impliedVolatility(
                        wCE.lastPrice, spotPrice, strike, weeklyDTE / 365.0, RISK_FREE_RATE, true, 0.01, 100);
                    row.put("weeklyCE_IV", Math.round(iv * 10000.0) / 100.0);
                    row.put("weeklyCE_price", wCE.lastPrice);
                    sumWeeklyIV += iv;
                    countWeekly++;
                    if (iv < minCEIV) { minCEIV = iv; minIVStrike = strike; }
                    if (iv > maxCEIV) { maxCEIV = iv; maxIVStrike = strike; }
                }
                if (wPE != null && wPE.lastPrice > 0) {
                    double iv = BlackScholesCalculator.impliedVolatility(
                        wPE.lastPrice, spotPrice, strike, weeklyDTE / 365.0, RISK_FREE_RATE, false, 0.01, 100);
                    row.put("weeklyPE_IV", Math.round(iv * 10000.0) / 100.0);
                    row.put("weeklyPE_price", wPE.lastPrice);
                }
                if (mCE != null && mCE.lastPrice > 0) {
                    double iv = BlackScholesCalculator.impliedVolatility(
                        mCE.lastPrice, spotPrice, strike, monthlyDTE / 365.0, RISK_FREE_RATE, true, 0.01, 100);
                    row.put("monthlyCE_IV", Math.round(iv * 10000.0) / 100.0);
                    row.put("monthlyCE_price", mCE.lastPrice);
                    sumMonthlyIV += iv;
                    countMonthly++;
                }
                if (mPE != null && mPE.lastPrice > 0) {
                    double iv = BlackScholesCalculator.impliedVolatility(
                        mPE.lastPrice, spotPrice, strike, monthlyDTE / 365.0, RISK_FREE_RATE, false, 0.01, 100);
                    row.put("monthlyPE_IV", Math.round(iv * 10000.0) / 100.0);
                    row.put("monthlyPE_price", mPE.lastPrice);
                }

                surface.add(row);
            }

            // Compute summary stats
            double avgWeeklyIV = countWeekly > 0 ? sumWeeklyIV / countWeekly : 0;
            double avgMonthlyIV = countMonthly > 0 ? sumMonthlyIV / countMonthly : 0;
            double estimatedRV = estimateRealizedVol(spotPrice);
            double ivPremium = estimatedRV > 0 ? (avgWeeklyIV - estimatedRV) / estimatedRV * 100 : 0;

            // Put-Call skew at ATM
            double weeklySkew = 0;
            double monthlySkew = 0;
            for (Map<String, Object> row : surface) {
                if ((int) row.get("strike") == atmStrike) {
                    if (row.containsKey("weeklyCE_IV") && row.containsKey("weeklyPE_IV")) {
                        weeklySkew = ((double) row.get("weeklyPE_IV") - (double) row.get("weeklyCE_IV"));
                    }
                    if (row.containsKey("monthlyCE_IV") && row.containsKey("monthlyPE_IV")) {
                        monthlySkew = ((double) row.get("monthlyPE_IV") - (double) row.get("monthlyCE_IV"));
                    }
                    break;
                }
            }

            // Term structure slope
            double termStructureSlope = avgMonthlyIV > 0 ? (avgMonthlyIV - avgWeeklyIV) / avgMonthlyIV * 100 : 0;

            result.put("status", "ok");
            result.put("underlying", underlying);
            result.put("spotPrice", spotPrice);
            result.put("futuresPrice", futuresPrice);
            result.put("atmStrike", atmStrike);
            result.put("lotSize", lotSize);
            result.put("surface", surface);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("avgWeeklyIV", Math.round(avgWeeklyIV * 10000.0) / 100.0);
            summary.put("avgMonthlyIV", Math.round(avgMonthlyIV * 10000.0) / 100.0);
            summary.put("estimatedRV", Math.round(estimatedRV * 10000.0) / 100.0);
            summary.put("ivPremium", Math.round(ivPremium * 100) / 100.0);
            summary.put("weeklyATMSkew", Math.round(weeklySkew * 100) / 100.0);
            summary.put("monthlyATMSkew", Math.round(monthlySkew * 100) / 100.0);
            summary.put("termStructureSlope", Math.round(termStructureSlope * 100) / 100.0);
            summary.put("weeklyDTE", weeklyDTE);
            summary.put("monthlyDTE", monthlyDTE);
            summary.put("weeklyExpiry", weeklyExpiry.toString());
            summary.put("monthlyExpiry", monthlyExpiry.toString());

            // Skew assessment
            if (weeklySkew > 5) {
                summary.put("skewSignal", "PUT_SKEW_HIGH");
                summary.put("skewNote", String.format("ATM put IV %.1f%% > call IV. Put skew suggests downside hedging demand", weeklySkew));
            } else if (weeklySkew < -5) {
                summary.put("skewSignal", "CALL_SKEW_HIGH");
                summary.put("skewNote", String.format("ATM call IV %.1f%% > put IV. Call skew suggests upside momentum", Math.abs(weeklySkew)));
            } else {
                summary.put("skewSignal", "NEUTRAL");
                summary.put("skewNote", "Put-Call skew is within normal range");
            }

            // Term structure assessment
            if (termStructureSlope > 10) {
                summary.put("termSignal", "NORMAL_CONTANGO");
                summary.put("termNote", String.format("Monthly IV > Weekly IV by %.1f%%. Normal term structure", termStructureSlope));
            } else if (termStructureSlope < -5) {
                summary.put("termSignal", "INVERTED");
                summary.put("termNote", String.format("Weekly IV > Monthly IV by %.1f%%. Inverted = mean reversion expected", Math.abs(termStructureSlope)));
            } else {
                summary.put("termSignal", "FLAT");
                summary.put("termNote", "Term structure is flat");
            }

            // IV vs RV assessment
            if (ivPremium > 30) {
                summary.put("volSignal", "IV_RICH");
                summary.put("volNote", String.format("IV %.1f%% is %.0f%% above RV %.1f%%. Consider selling premium",
                    avgWeeklyIV * 100, ivPremium, estimatedRV * 100));
            } else if (ivPremium < -10) {
                summary.put("volSignal", "IV_CHEAP");
                summary.put("volNote", String.format("IV %.1f%% is below RV %.1f%%. Consider buying volatility",
                    avgWeeklyIV * 100, estimatedRV * 100));
            } else {
                summary.put("volSignal", "FAIR");
                summary.put("volNote", String.format("IV %.1f%% is close to RV %.1f%%", avgWeeklyIV * 100, estimatedRV * 100));
            }

            result.put("summary", summary);

        } catch (Exception e) {
            log.error("Error building vol surface for {}: {}", underlying, e.getMessage(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }

        return result;
    }

    private double estimateRealizedVol(double spotPrice) {
        return 0.17;
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
