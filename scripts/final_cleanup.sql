-- Also remove dep #14 (paper duplicate of EMA50D)
DELETE FROM deployments WHERE id = 14;

-- Final verification
SELECT id, name, strategy_type, timeframe, enabled FROM strategies ORDER BY id;
SELECT '---';
SELECT id, strategy_id, capital, mode, status FROM deployments ORDER BY id;
