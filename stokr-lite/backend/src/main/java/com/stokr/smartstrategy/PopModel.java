package com.stokr.smartstrategy;

import com.stokr.arbitrage.ArbitrageCosts;
import com.stokr.arbitrage.BlackScholesCalculator;
import com.stokr.arbitrage.OptionChainService;
import com.stokr.marketdata.MarketCalendar;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Probability-of-profit (POP) at expiry under the lognormal model, driven by the ATM implied vol
 * read from the same option chain the scanner priced from. Replaces the hard-coded
 * "50 + distance × k" win-rate heuristics: this is a model probability, only as good as the IV.
 */
public final class PopModel {

    /** Used when the ATM IV cannot be solved (missing quote, zero time). */
    public static final double FALLBACK_IV = 0.15;

    private PopModel() {}

    /** Years until 15:30 IST on the expiry date, floored at one minute. */
    public static double yearsToExpiry(LocalDate expiry, ZonedDateTime now) {
        ZonedDateTime exp = expiry.atTime(MarketCalendar.CLOSE).atZone(MarketCalendar.IST);
        double minutes = Math.max(1, Duration.between(now.withZoneSameInstant(MarketCalendar.IST), exp).toMinutes());
        return minutes / (365.0 * 24 * 60);
    }

    public static double yearsToExpiry(LocalDate expiry) {
        return yearsToExpiry(expiry, ZonedDateTime.now(MarketCalendar.IST));
    }

    /** Average of the ATM call and put implied vols (decimal, e.g. 0.14); FALLBACK_IV if unsolvable. */
    public static double atmIv(OptionChainService.OptionQuote ce, OptionChainService.OptionQuote pe,
                               double spot, int atmStrike, double years) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 2; i++) {
            OptionChainService.OptionQuote q = i == 0 ? ce : pe;
            if (q == null) continue;
            double px = mid(q);
            if (px <= 0) continue;
            double iv = BlackScholesCalculator.impliedVolatility(px, spot, atmStrike, years,
                ArbitrageCosts.RISK_FREE_RATE, i == 0, 0.01, 50);
            if (iv > 0.01 && iv < 5.0) { sum += iv; n++; }
        }
        return n > 0 ? sum / n : FALLBACK_IV;
    }

    private static double mid(OptionChainService.OptionQuote q) {
        if (q.bid > 0 && q.ask > 0) return (q.bid + q.ask) / 2.0;
        return q.lastPrice;
    }

    /** P(settle inside (low, high)); a NaN bound means that side is unbounded. Returned as a percentage. */
    public static double popPct(double spot, double low, double high, double years, double iv) {
        double r = ArbitrageCosts.RISK_FREE_RATE;
        double p;
        boolean hasLow = !Double.isNaN(low), hasHigh = !Double.isNaN(high);
        if (hasLow && hasHigh) {
            if (high <= low) return 0;
            p = BlackScholesCalculator.probabilityInRange(spot, low, high, years, r, iv);
        } else if (hasLow) {
            p = BlackScholesCalculator.probabilityAbove(spot, low, years, r, iv);
        } else if (hasHigh) {
            p = BlackScholesCalculator.probabilityBelow(spot, high, years, r, iv);
        } else {
            p = 1;
        }
        return Math.round(p * 1000.0) / 10.0;
    }
}
