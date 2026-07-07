-- V35: Seed Institutional Footprint strategy

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('Institutional Footprint — VSA Smart Money Engine',
 'Reads institutional accumulation/distribution via 4-component scoring on 15-min candles: '
 'Volume-Spread Analysis (30pts) + Sector Dominance (25pts) + Order Flow Proxy (25pts) + Setup Quality (20pts). '
 'Score >= 70 = A+ signal. Expected: 55-60% WR, 3-5 signals/day, 1.8:1 R:R.',
 'INSTITUTIONAL_FOOTPRINT', 'EQUITY',
 '{"max_positions": 2, "capital_per_trade": 25000, "min_score": 70, "trail_trigger": 1.2, "trail_distance": 1.0}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
