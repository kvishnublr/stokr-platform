\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on

\echo ===STRATEGY_TOTALS===
SELECT
  strategy_name,
  count(*) as total_signals,
  count(*) FILTER (WHERE outcome_status = 'TARGET_HIT') as target_hit,
  count(*) FILTER (WHERE outcome_status IN ('SL_HIT','STOPLOSS_HIT')) as sl_hit,
  count(*) FILTER (WHERE outcome_status = 'PRESSURE_EXIT') as pressure_exit,
  count(*) FILTER (WHERE outcome_status = 'FEED_PROTECTION') as feed_protection,
  count(*) FILTER (WHERE outcome_status = 'MANUAL') as manual,
  count(*) FILTER (WHERE outcome_status IN ('RUNNING','PENDING') OR outcome_status IS NULL) as still_open,
  count(*) FILTER (WHERE outcome_status = 'TIME_EXIT') as time_exit,
  count(*) FILTER (WHERE outcome_status = 'BREAKEVEN_EXIT') as breakeven,
  round(sum(COALESCE(realized_pnl,0))::numeric, 2) as total_pnl,
  round(avg(COALESCE(confidence_score,0))::numeric, 1) as avg_conf
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND is_test_trade = false AND backtest_run_id IS NULL
GROUP BY strategy_name
ORDER BY total_signals DESC;

\echo ===SIGNAL_DETAIL===
SELECT
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  strategy_name, symbol, signal_type, pipeline,
  COALESCE(outcome_status, 'PENDING') as outcome,
  round(COALESCE(confidence_score,0)::numeric, 1) as conf,
  round(COALESCE(realized_pnl,0)::numeric, 2) as pnl,
  COALESCE(hit_target::text,'f') as tgt,
  COALESCE(hit_stoploss::text,'f') as sl
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND is_test_trade = false AND backtest_run_id IS NULL
ORDER BY created_at;

\echo ===OMS_SUMMARY===
SELECT
  COALESCE(o.strategy_key, 'NO_KEY') as strategy,
  o.execution_mode::text as mode,
  o.state::text as state,
  count(*) as cnt
FROM oms_orders o
WHERE o.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND o.deleted = false
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

\echo ===OMS_LIVE_FILLS===
SELECT
  to_char(o.created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  COALESCE(o.strategy_key, s.strategy_name) as strategy,
  o.symbol, o.side, o.state::text,
  COALESCE(o.broker_external_order_id, 'none') as broker_id
FROM oms_orders o
LEFT JOIN strategy_signals s ON s.id = o.signal_id
WHERE o.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND o.deleted = false
  AND o.execution_mode = 'LIVE'
  AND o.state IN ('FILLED','ACCEPTED','PARTIALLY_FILLED')
ORDER BY o.created_at;
