\set ON_ERROR_STOP on

\echo '=== BACKTEST_RUN_ID on today signals ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT
  COUNT(*) FILTER (WHERE backtest_run_id IS NOT NULL) AS with_backtest_run,
  COUNT(*) FILTER (WHERE backtest_run_id IS NULL) AS no_backtest_run,
  COUNT(*) FILTER (WHERE signal_source = 'REPLAY' AND backtest_run_id IS NULL) AS replay_no_backtest
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc;

\echo '=== vishnualgo signals today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo')
SELECT COUNT(*) AS total,
  COUNT(*) FILTER (WHERE signal_source = 'LIVE') AS live,
  COUNT(*) FILTER (WHERE signal_source = 'REPLAY') AS replay,
  COUNT(*) FILTER (WHERE signal_source = 'PAPER') AS paper
FROM strategy_signals ss, bounds, u
WHERE ss.deleted = FALSE AND ss.user_id = u.id AND ss.created_at >= bounds.start_utc;

\echo '=== vishnualgo per strategy today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo')
SELECT strategy_name, signal_source, COUNT(*)
FROM strategy_signals ss, bounds, u
WHERE ss.deleted = FALSE AND ss.user_id = u.id AND ss.created_at >= bounds.start_utc
GROUP BY 1, 2 ORDER BY 3 DESC;

\echo '=== NULL user_id signals today by source ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT COALESCE(signal_source::text,'null') AS src, COUNT(*)
FROM strategy_signals, bounds
WHERE deleted = FALSE AND user_id IS NULL AND created_at >= bounds.start_utc
GROUP BY 1 ORDER BY 2 DESC;

\echo '=== RUNNING instances vishnualgo ==='
SELECT sd.strategy_key, si.status, si.symbol, si.id
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id = si.definition_id
JOIN auth_users u ON u.id = si.user_id
WHERE u.username = 'vishnualgo' AND si.deleted = FALSE
ORDER BY sd.strategy_key;

\echo '=== ACTIVE BINDINGS count ==='
SELECT COUNT(*) AS active_bindings FROM strategy_runtime_bindings WHERE runtime_enabled = TRUE;

\echo '=== BINDINGS by universe ==='
SELECT sc.strategy_key, ug.group_key, COUNT(*) 
FROM strategy_runtime_bindings srb
JOIN strategy_catalog sc ON sc.id = srb.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = srb.universe_group_id
WHERE srb.runtime_enabled = TRUE
GROUP BY 1, 2 ORDER BY 1, 2;

\echo '=== NIFTY universe symbol counts ==='
SELECT ug.group_key, COUNT(*) 
FROM strategy_universe_symbols sus
JOIN strategy_universe_groups ug ON ug.id = sus.universe_group_id
WHERE ug.group_key IN ('NIFTY_50','NIFTY_100')
GROUP BY 1;

\echo '=== Signals per minute peak today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
buckets AS (
  SELECT date_trunc('minute', created_at) AS m, COUNT(*) c
  FROM strategy_signals, bounds
  WHERE deleted = FALSE AND created_at >= bounds.start_utc
  GROUP BY 1
)
SELECT MAX(c) AS peak_per_minute, SUM(c) AS total FROM buckets;

\echo '=== CASH_15M top symbols ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT symbol, signal_source, COUNT(*)
FROM strategy_signals, bounds
WHERE deleted = FALSE AND strategy_name = 'CASH_15M_BREAKOUT_TEST' AND created_at >= bounds.start_utc
GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 15;
