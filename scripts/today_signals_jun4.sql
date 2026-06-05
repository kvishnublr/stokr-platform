\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on
SELECT strategy_name,
  count(*) as total,
  count(*) FILTER (WHERE outcome_status = 'TARGET_HIT') as targets,
  count(*) FILTER (WHERE outcome_status IN ('SL_HIT','STOPLOSS_HIT')) as sl,
  count(*) FILTER (WHERE outcome_status = 'PRESSURE_EXIT') as pressure,
  round(sum(COALESCE(realized_pnl,0))::numeric,2) as pnl
FROM strategy_signals
WHERE created_at >= '2026-06-04 00:00:00+00' AND created_at < '2026-06-05 00:00:00+00'
  AND deleted=false AND is_test_trade=false AND backtest_run_id IS NULL
GROUP BY strategy_name ORDER BY total DESC;
