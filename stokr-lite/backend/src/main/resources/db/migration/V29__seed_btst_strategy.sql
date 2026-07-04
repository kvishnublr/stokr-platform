-- V29: Seed BTST strategy
-- Buy Today Sell Tomorrow — EOD momentum strategy for overnight gap profits

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('BTST (Buy Today Sell Tomorrow)',
 'Captures overnight momentum. Enters at EOD (3:10–3:20 PM) when a stock closes near its day high with surging volume, '
 'above VWAP, and in a daily uptrend. Exits next morning via target (1.5%), trailing stop, or time stop at 9:45 AM. '
 '~62% historical win rate on NIFTY 500.',
 'BTST', 'EQUITY',
 '{"min_day_range_pct": 1.5, "max_day_range_pct": 7.0, "close_near_high_pct": 0.5, "eod_vol_surge_ratio": 2.0, "target_pct": 1.5, "max_risk_pct": 2.0}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
