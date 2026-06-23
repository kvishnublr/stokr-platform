-- =============================================================
-- Stokr Lite - Seed Data (Core Strategies)
-- =============================================================

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('Opening Range Breakout', 
 'Trades breakouts from the first 15-minute opening range. Buys when price breaks above range high, sells when below range low.',
 'ORB', 'EQUITY', 
 '{"orb_period_minutes": 15, "volume_multiplier": 2.0, "stop_loss_pct": 0.5, "target_pct": 1.0}', 
 true, NOW(), NOW()),

('VWAP Bounce', 
 'Enters when price bounces off VWAP with volume confirmation. Uses VWAP as dynamic support/resistance.',
 'VWAP_BOUNCE', 'EQUITY',
 '{"vwap_deviation_pct": 0.3, "volume_confirm_multiplier": 1.5, "stop_loss_pct": 0.4, "target_pct": 0.8}',
 true, NOW(), NOW()),

('Gap Fill',
 'Trades stocks that gap up or down at open, expecting the gap to fill during the session.',
 'GAP_FILL', 'EQUITY',
 '{"min_gap_pct": 1.0, "max_gap_pct": 3.0, "volume_threshold": 50000, "stop_loss_pct": 0.6, "target_pct": 1.2}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
