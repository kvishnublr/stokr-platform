INSERT INTO strategies (name, strategy_type, description, asset_class, enabled, timeframe)
VALUES ('Oversold Bounce', 'OVERSOLD_BOUNCE', 'Buy stocks that dropped >3%, sell next day. Data-driven mean reversion.', 'EQUITY', true, 'DAILY')
ON CONFLICT (name) DO UPDATE SET timeframe = 'DAILY', enabled = true, strategy_type = 'OVERSOLD_BOUNCE';

-- Map to NIFTY_50 universe
INSERT INTO strategy_universe_mappings (strategy_id, universe_group_id, runtime_enabled, max_positions, risk_profile)
SELECT s.id, ug.id, true, 3, 'MEDIUM'
FROM strategies s, universe_groups ug
WHERE s.strategy_type = 'OVERSOLD_BOUNCE' AND ug.group_key = 'NIFTY_50'
AND NOT EXISTS (SELECT 1 FROM strategy_universe_mappings WHERE strategy_id = s.id);
