package com.stokr.arbitrage;

/**
 * Black-Scholes theoretical option pricing engine.
 * Used to detect mispricings in real-time option chains.
 */
public class BlackScholesCalculator {

    private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);

    /**
     * Standard normal cumulative distribution function
     */
    public static double normCDF(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    /**
     * Approximation of error function (Abramowitz and Stegun)
     */
    private static double erf(double x) {
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        double sign = (x >= 0) ? 1 : -1;
        x = Math.abs(x);

        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return sign * y;
    }

    /**
     * Standard normal probability density function
     */
    private static double normPDF(double x) {
        return Math.exp(-0.5 * x * x) / SQRT_2PI;
    }

    /**
     * Calculate call option price
     * @param S Spot price
     * @param K Strike price
     * @param T Time to expiry in years
     * @param r Risk-free rate (annualized, e.g., 0.065 for 6.5%)
     * @param sigma Volatility (annualized, e.g., 0.20 for 20%)
     * @return Theoretical call price
     */
    public static double callPrice(double S, double K, double T, double r, double sigma) {
        if (T <= 0) return Math.max(S - K, 0);
        if (sigma <= 0) return Math.max(S - K * Math.exp(-r * T), 0);

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);

        return S * normCDF(d1) - K * Math.exp(-r * T) * normCDF(d2);
    }

    /**
     * Calculate put option price
     */
    public static double putPrice(double S, double K, double T, double r, double sigma) {
        if (T <= 0) return Math.max(K - S, 0);
        if (sigma <= 0) return Math.max(K * Math.exp(-r * T) - S, 0);

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);

        return K * Math.exp(-r * T) * normCDF(-d2) - S * normCDF(-d1);
    }

    /**
     * Calculate all Greeks for a call option
     */
    public static Greeks callGreeks(double S, double K, double T, double r, double sigma) {
        if (T <= 0 || sigma <= 0) {
            return new Greeks(0, 0, 0, 0);
        }
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);

        double delta = normCDF(d1);
        double gamma = normPDF(d1) / (S * sigma * Math.sqrt(T));
        double theta = -(S * normPDF(d1) * sigma) / (2 * Math.sqrt(T))
                       - r * K * Math.exp(-r * T) * normCDF(d2);
        double vega = S * normPDF(d1) * Math.sqrt(T);

        return new Greeks(delta, gamma, theta / 365, vega / 100);
    }

    /**
     * Calculate implied volatility using Newton-Raphson method
     */
    public static double impliedVolatility(double marketPrice, double S, double K, double T, double r,
                                            boolean isCall, double tolerance, int maxIterations) {
        if (T <= 0 || marketPrice <= 0) return 0;

        double sigma = 0.3; // initial guess
        for (int i = 0; i < maxIterations; i++) {
            double theoretical = isCall
                ? callPrice(S, K, T, r, sigma)
                : putPrice(S, K, T, r, sigma);

            double diff = theoretical - marketPrice;
            if (Math.abs(diff) < tolerance) return sigma;

            // Vega for Newton-Raphson
            double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
            double vega = S * normPDF(d1) * Math.sqrt(T);

            if (vega < 1e-10) break; // avoid division by zero

            sigma -= diff / vega;
            sigma = Math.max(0.01, Math.min(sigma, 5.0)); // clamp
        }
        return sigma;
    }

    /**
     * Probability that the underlying settles within [lower, upper] at expiry, under the
     * standard Black-Scholes risk-neutral lognormal assumption. This is a MODEL probability
     * driven by the implied volatility input, not a backtested or historical win rate --
     * it's the same "POP" figure every options analytics platform (Sensibull, AlgoTest,
     * ToS) computes the same way, and it's only as good as the IV estimate feeding it.
     */
    public static double probabilityInRange(double S, double lower, double upper, double T, double r, double sigma) {
        if (T <= 0 || sigma <= 0 || S <= 0) return 0;
        double denom = sigma * Math.sqrt(T);
        double drift = (r - 0.5 * sigma * sigma) * T;
        double dUpper = (Math.log(upper / S) - drift) / denom;
        double dLower = (Math.log(lower / S) - drift) / denom;
        return Math.max(0, Math.min(1, normCDF(dUpper) - normCDF(dLower)));
    }

    /** P(underlying settles above `threshold` at expiry), same lognormal model as probabilityInRange. */
    public static double probabilityAbove(double S, double threshold, double T, double r, double sigma) {
        if (T <= 0 || sigma <= 0 || S <= 0) return threshold < S ? 1 : 0;
        double d = (Math.log(threshold / S) - (r - 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        return Math.max(0, Math.min(1, 1 - normCDF(d)));
    }

    /** P(underlying settles below `threshold` at expiry), same lognormal model as probabilityInRange. */
    public static double probabilityBelow(double S, double threshold, double T, double r, double sigma) {
        return Math.max(0, Math.min(1, 1 - probabilityAbove(S, threshold, T, r, sigma)));
    }

    /**
     * Put-Call Parity: synthetic futures price from call and put.
     * For European options priced against a futures price F (not spot), parity is
     * C - P = e^(-rT) * (F - K), so F = K + (C - P) * e^(rT).
     * (Previously this discounted K by e^(-rT) without growing (C-P), which compares
     * a synthetic SPOT price against the FUTURES price and inflates the apparent
     * edge by the futures cost-of-carry basis ~ spot * r * T.)
     */
    public static double syntheticFutures(double callPrice, double putPrice, double K, double r, double T) {
        return K + (callPrice - putPrice) * Math.exp(r * T);
    }

    /**
     * Parity deviation in points
     */
    public static double parityDeviation(double callPrice, double putPrice, double K, double r, double T,
                                         double actualFutures) {
        double synthetic = syntheticFutures(callPrice, putPrice, K, r, T);
        return synthetic - actualFutures;
    }

    /**
     * Greeks container
     */
    public static class Greeks {
        public final double delta;
        public final double gamma;
        public final double theta;   // per day
        public final double vega;    // per 1% vol change

        public Greeks(double delta, double gamma, double theta, double vega) {
            this.delta = delta;
            this.gamma = gamma;
            this.theta = theta;
            this.vega = vega;
        }
    }
}
