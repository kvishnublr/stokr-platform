-- Seed CASH_IGNITION strategy (3-stage-funnel intraday with master scoring)
INSERT INTO strategies (id, name, description, strategy_type, params_schema, asset_class, enabled, created_at, updated_at)
VALUES (
  7,
  'Cash Liquidity Ignition',
  'Dynamic 3-stage funnel: Candidate(+2 vol/+2 range/+2 breakout) -> Confirmation(+1 nifty/+1 close) -> Execution. Master score 0-10. Regime veto (ADX<15). Entry above candle high, SL below candle low. Dynamic trail + time-stop.',
  'CASH_IGNITION',
  '{}',
  'EQUITY', true, NOW(), NOW()
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  strategy_type = EXCLUDED.strategy_type,
  enabled = true;

-- Map CASH_IGNITION to NIFTY_50 universe
INSERT INTO strategy_universe_mappings (strategy_id, universe_group_id, runtime_enabled, max_positions)
SELECT 7, g.id, true, 3
FROM universe_groups g
WHERE g.group_key = 'NIFTY_50'
  AND NOT EXISTS (
    SELECT 1 FROM strategy_universe_mappings m
    WHERE m.strategy_id = 7 AND m.universe_group_id = g.id
  );
