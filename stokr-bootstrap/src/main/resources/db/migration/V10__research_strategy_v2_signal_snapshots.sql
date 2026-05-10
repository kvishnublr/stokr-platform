-- Signal research snapshots + Mean Reversion v2 catalog entry

ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS parameter_snapshot_json TEXT;
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS indicator_snapshot_json TEXT;

INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json, category, risk_level, enabled, visible_to_users
)
SELECT '22222222-2222-2222-2222-222222222206', NOW(), NOW(), 0, FALSE,
       'MEAN_REVERSION_V2',
       'Mean reversion v2',
       'Relaxed RSI bands with wider range envelope vs classic range fade',
       '{"variant":"V2","rsiBuyMax":"35","rsiSellMin":"65","maxRangeWidthPct":"1.45"}',
       'MEAN_REVERSION',
       'MEDIUM',
       TRUE,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'MEAN_REVERSION_V2');
