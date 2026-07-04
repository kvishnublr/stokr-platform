-- V34: Seed Momentum Surge strategy

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('Momentum Surge — 5-Min High-Conviction Intraday',
 '5-condition confluence on 5-min candles: new 30-period high + 2.5x volume + above VWAP + EMA5>EMA20 + relative strength vs NIFTY. '
 'Expected: 60% WR, 3-8 signals/day, ₹60 net/signal on ₹12K. Replaces QuickFlip with professional-grade momentum strategy.',
 'MOMENTUM_SURGE', 'EQUITY',
 '{"max_positions": 2, "capital_per_trade": 12000, "trail_trigger": 0.8, "trail_distance": 0.4}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
