-- V33: Seed QuickFlip strategy

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('QuickFlip — Multi-Pattern Cash Scanner',
 'Composite scanner for rapid cash market moves. Watches 4 patterns simultaneously every 60 seconds: '
 'VWAP Bounce (63% WR), Afternoon Range Break (67% WR), Volume Explosion (55% WR), Opening Drive (60% WR). '
 'Targets 0.8-1.8% per trade. Hold time: 10-45 minutes. 10-15 signals/day. Composite ~60% WR.',
 'QUICK_FLIP', 'EQUITY',
 '{"max_positions": 5, "capital_per_trade": 20000, "trail_trigger": 0.5, "trail_distance": 0.2}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
