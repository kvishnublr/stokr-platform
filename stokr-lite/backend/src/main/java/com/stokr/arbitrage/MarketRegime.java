package com.stokr.arbitrage;

/**
 * Market Regime classification for dynamic strategy selection and capital protection.
 */
public enum MarketRegime {
    RANGE_BOUND_CHOP, // Sideways / Consolidation: Spot within +/-0.30% of VWAP
    BULLISH_TREND,    // Strong Momentum Uptrend: Spot > VWAP + 0.30%
    BEARISH_TREND,    // Strong Momentum Downtrend: Spot < VWAP - 0.30%
    VOLATILITY_SHOCK  // High volatility / Opening 15m turbulence / Extreme deviation
}