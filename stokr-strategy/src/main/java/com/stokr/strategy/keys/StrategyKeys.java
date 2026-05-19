package com.stokr.strategy.keys;

/**
 * Legacy compatibility layer — hardcoded strategy keys for the existing signal generators.
 *
 * <p><b>DO NOT add new keys here.</b> New strategies are created via the admin panel and
 * resolved from {@code strategy_definitions} at runtime. This class exists only to preserve
 * compile-time references in the existing signal generators and backtest routing until those
 * classes are migrated to DB-driven catalog lookups.</p>
 *
 * <p>Runtime resolution order:
 * <ol>
 *   <li>DB lookup via {@code strategy_definitions.strategy_key}</li>
 *   <li>Fallback to constants in this class (legacy path)</li>
 * </ol>
 * </p>
 *
 * @deprecated Use the admin-managed strategy catalog ({@code strategy_definitions} table).
 *             New strategies should be created through {@code POST /api/admin/strategies}.
 */
@Deprecated(since = "V31", forRemoval = false)
public final class StrategyKeys {

    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String MEAN_REVERSION_RANGE_FADE = "MEAN_REVERSION_RANGE_FADE";
    /** Relaxed RSI band + wider range envelope vs {@link #MEAN_REVERSION_RANGE_FADE}.
     * @deprecated resolved from DB catalog */
    @Deprecated public static final String MEAN_REVERSION_V2         = "MEAN_REVERSION_V2";
    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String OPENING_RANGE_BREAKOUT    = "OPENING_RANGE_BREAKOUT";
    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String VWAP_MEAN_REVERSION       = "VWAP_MEAN_REVERSION";
    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String MOMENTUM_BREAKOUT         = "MOMENTUM_BREAKOUT";
    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String EMA_TREND_FOLLOW          = "EMA_TREND_FOLLOW";
    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String CASH_15M_BREAKOUT_TEST    = "CASH_15M_BREAKOUT_TEST";
    /** @deprecated resolved from DB catalog */
    @Deprecated public static final String COMMODITIES_10M_BREAKOUT  = "COMMODITIES_10M_BREAKOUT";

    private StrategyKeys() {}
}
