-- Seed VWAP_REVERSION strategy for self-contained VWAP reversion scanner
INSERT INTO strategies (id, name, description, strategy_type, params_schema, asset_class, enabled, created_at, updated_at)
VALUES (
  6,
  'VWAP Reversion',
  'Mean reversion when price deviates >0.8% from VWAP with volume confirmation. Enters long below VWAP, short above VWAP. 2:1 R:R.',
  'VWAP_REVERSION',
  '{"vwap_deviation_pct": 0.8, "volume_confirm_multiplier": 1.5, "stop_loss_pct": 0.7, "target_pct": 1.4}',
  'EQUITY', true, NOW(), NOW()
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  strategy_type = EXCLUDED.strategy_type,
  enabled = true;

-- Map VWAP_REVERSION to NIFTY_500 universe
INSERT INTO strategy_universe_mappings (strategy_id, universe_group_id, runtime_enabled, max_positions)
SELECT 6, g.id, true, 3
FROM universe_groups g
WHERE g.group_key = 'NIFTY_500'
  AND NOT EXISTS (
    SELECT 1 FROM strategy_universe_mappings m
    WHERE m.strategy_id = 6 AND m.universe_group_id = g.id
  );
