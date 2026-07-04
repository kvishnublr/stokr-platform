-- V30: Seed 20-Day Breakout Swing strategy

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('20-Day Breakout Swing',
 'Darvas Box style positional strategy. Enters when a stock closes above its 20-day high with 2x volume surge, '
 'above 20 & 50 EMA. Holds 1-10 days. Target +8%, SL max(20EMA, entry-3%). ~55% win rate, ~6% avg win. '
 'Best on NIFTY 100 + NEXT 50 liquid large-caps.',
 'TWENTY_DAY_BREAKOUT', 'EQUITY',
 '{"breakout_period": 20, "volume_surge_ratio": 2.0, "target_pct": 8.0, "max_sl_pct": 3.0, "time_stop_days": 10}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
