-- Ensure all 3 backtestable strategies exist with correct types
INSERT INTO strategies (id, name, strategy_type, description, enabled)
VALUES
  (1, 'VWAP Triple Confirmation', 'VWAP_TRIPLE',     'Pullback to VWAP with RSI + volume confirmation', true),
  (3, 'ORB Breakout',             'ORB_V',           'Opening-range breakout with trailing stop',        true),
  (4, 'Morning Surge',            'MORNING_SURGE',   'High-volume ORB breakout in first hour (9:30-10:30)', true)
ON CONFLICT (id) DO UPDATE SET
  strategy_type = EXCLUDED.strategy_type,
  description   = EXCLUDED.description,
  enabled       = true;

-- Add NIFTY_500 universe group (symbols served from in-memory list, not DB)
INSERT INTO universe_groups (group_key, display_name, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT 'NIFTY_500', 'NIFTY 500', 'INDEX_CONSTITUENTS', 'NSE', 'EQUITY', 'NSE', 'EQ', false, true
WHERE NOT EXISTS (SELECT 1 FROM universe_groups WHERE group_key = 'NIFTY_500');

-- Map backtestable strategies to NIFTY_500 as well
INSERT INTO strategy_universe_mappings (strategy_id, universe_group_id, runtime_enabled, max_positions)
SELECT s.id, g.id, true, 3
FROM strategies s, universe_groups g
WHERE g.group_key = 'NIFTY_500'
  AND s.id IN (1, 3, 4)
  AND NOT EXISTS (
    SELECT 1 FROM strategy_universe_mappings m
    WHERE m.strategy_id = s.id AND m.universe_group_id = g.id
  );
