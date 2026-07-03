-- Seed AI Ensemble Intraday Strategy
INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at)
VALUES (
    'AI Ensemble Intraday',
    '12-factor adaptive scoring strategy combining volume momentum, price momentum, RSI, VWAP deviation, ORB breakout, candle body ratio, ATR volatility, EMA trend, gap, volume-price trend, streak strength, and intraday pattern with regime-adaptive weights (trending/ranging/high-vol).',
    'AI_ENSEMBLE',
    'EQUITY',
    '{"threshold_long": 0.62, "threshold_short": 0.38, "atr_sl_multiplier": 1.5, "min_rr_ratio": 2.0, "max_rr_ratio": 3.0, "atr_high_vol_pct": 1.5, "atr_low_vol_pct": 0.25}',
    true,
    NOW(),
    NOW()
) ON CONFLICT (name) DO NOTHING;

-- Ensure default config exists for the AI Ensemble strategy
INSERT INTO strategy_configs (strategy_id, allocated_capital, max_positions, max_trade_quantity, force_fixed_qty, fixed_qty, sizing_mode, max_capital_per_trade, max_risk_per_trade_pct, daily_loss_limit, cooldown_minutes, live_enabled, paper_enabled, enabled, created_at, updated_at)
SELECT s.id, 100000, 3, 1, true, 1, 'FIXED_QUANTITY', 50000, 1.0, 5000, 15, false, true, true, NOW(), NOW()
FROM strategies s
WHERE s.strategy_type = 'AI_ENSEMBLE'
AND NOT EXISTS (
    SELECT 1 FROM strategy_configs sc WHERE sc.strategy_id = s.id
);
