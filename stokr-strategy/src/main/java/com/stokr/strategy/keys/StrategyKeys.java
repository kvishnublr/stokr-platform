package com.stokr.strategy.keys;

/**
 * Canonical catalog keys for strategies (catalog + backtest routing).
 */
public final class StrategyKeys {

    public static final String MEAN_REVERSION_RANGE_FADE = "MEAN_REVERSION_RANGE_FADE";
    /** Relaxed RSI band + wider range envelope vs {@link #MEAN_REVERSION_RANGE_FADE}. */
    public static final String MEAN_REVERSION_V2 = "MEAN_REVERSION_V2";
    public static final String OPENING_RANGE_BREAKOUT = "OPENING_RANGE_BREAKOUT";
    public static final String VWAP_MEAN_REVERSION = "VWAP_MEAN_REVERSION";
    public static final String MOMENTUM_BREAKOUT = "MOMENTUM_BREAKOUT";
    public static final String EMA_TREND_FOLLOW = "EMA_TREND_FOLLOW";

    private StrategyKeys() {
    }
}
