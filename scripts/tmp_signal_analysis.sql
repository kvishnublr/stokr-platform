\set ON_ERROR_STOP on
\timing off

-- User lookup
\echo '=== AUTH USERS (vishnu) ==='
SELECT id, username, email FROM auth_users WHERE username ILIKE '%vishnu%' OR email ILIKE '%vishnu%' LIMIT 10;

\echo '=== TODAY IST boundary ==='
SELECT
  (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS today_ist_start_utc,
  now() AS now_utc;

\echo '=== SIGNALS TODAY (all users, IST day) ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT
  COUNT(*) AS total_today,
  COUNT(*) FILTER (WHERE signal_source = 'LIVE' OR (signal_source IS NULL AND pipeline = 'LIVE')) AS live_like,
  COUNT(*) FILTER (WHERE signal_source = 'REPLAY') AS replay,
  COUNT(*) FILTER (WHERE signal_source = 'PAPER' OR pipeline = 'PAPER') AS paper,
  COUNT(*) FILTER (WHERE signal_source = 'LAB' OR is_test_trade) AS lab_or_test,
  COUNT(*) FILTER (WHERE signal_type = 'HOLD') AS hold,
  COUNT(*) FILTER (WHERE signal_type IN ('BUY','SELL','EXIT')) AS actionable
FROM strategy_signals, bounds
WHERE deleted = FALSE
  AND created_at >= bounds.start_utc;

\echo '=== SIGNALS LAST 7 DAYS ==='
SELECT
  COUNT(*) AS total_7d,
  COUNT(*) FILTER (WHERE signal_source = 'LIVE' OR (signal_source IS NULL AND pipeline = 'LIVE')) AS live_like,
  COUNT(*) FILTER (WHERE signal_source = 'REPLAY') AS replay,
  COUNT(*) FILTER (WHERE signal_source = 'PAPER' OR pipeline = 'PAPER') AS paper,
  COUNT(*) FILTER (WHERE signal_source = 'LAB' OR is_test_trade) AS lab_or_test
FROM strategy_signals
WHERE deleted = FALSE
  AND created_at >= now() - interval '7 days';

\echo '=== PER STRATEGY TODAY (IST) ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT
  COALESCE(strategy_name, '(null)') AS strategy_key,
  COUNT(*) AS cnt_today,
  COUNT(*) FILTER (WHERE signal_source = 'LIVE' OR (signal_source IS NULL AND pipeline = 'LIVE')) AS live_cnt,
  COUNT(*) FILTER (WHERE signal_source = 'REPLAY') AS replay_cnt,
  COUNT(*) FILTER (WHERE signal_type = 'BUY') AS buy,
  COUNT(*) FILTER (WHERE signal_type = 'SELL') AS sell,
  COUNT(*) FILTER (WHERE signal_type = 'EXIT') AS exit,
  COUNT(*) FILTER (WHERE signal_type = 'HOLD') AS hold
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc
GROUP BY 1 ORDER BY cnt_today DESC;

\echo '=== PER STRATEGY 7D ==='
SELECT
  COALESCE(strategy_name, '(null)') AS strategy_key,
  COUNT(*) AS cnt_7d,
  COUNT(*) FILTER (WHERE signal_source = 'LIVE' OR (signal_source IS NULL AND pipeline = 'LIVE')) AS live_cnt,
  COUNT(*) FILTER (WHERE signal_source = 'REPLAY') AS replay_cnt
FROM strategy_signals
WHERE deleted = FALSE AND created_at >= now() - interval '7 days'
GROUP BY 1 ORDER BY cnt_7d DESC;

\echo '=== TOP SYMBOLS TODAY ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT symbol, COUNT(*) AS cnt
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc AND symbol IS NOT NULL
GROUP BY 1 ORDER BY cnt DESC LIMIT 25;

\echo '=== DUPLICATE CANDIDATES TODAY (same strategy+symbol+type within 15m) ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
ordered AS (
  SELECT
    strategy_name, symbol, signal_type, created_at,
    LAG(created_at) OVER (PARTITION BY strategy_name, symbol, signal_type ORDER BY created_at) AS prev_at
  FROM strategy_signals, bounds
  WHERE deleted = FALSE
    AND created_at >= bounds.start_utc
    AND is_test_trade = FALSE
    AND backtest_run_id IS NULL
    AND (signal_source IS NULL OR signal_source NOT IN ('REPLAY', 'LAB'))
)
SELECT COUNT(*) AS pairs_within_15m
FROM ordered
WHERE prev_at IS NOT NULL AND created_at - prev_at < interval '15 minutes';

\echo '=== RUNNING INSTANCES ==='
SELECT si.strategy_key, si.state, COUNT(*)
FROM strategy_instances si
WHERE si.deleted = FALSE
GROUP BY 1, 2 ORDER BY 1, 2;

\echo '=== SIGNALS TODAY FOR vishnualgo user_id ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (
  SELECT id FROM auth_users WHERE username = 'vishnualgo' LIMIT 1
)
SELECT
  (SELECT id FROM u) AS user_id,
  COUNT(*) AS total_today,
  COUNT(*) FILTER (WHERE ss.signal_source = 'LIVE' OR (ss.signal_source IS NULL AND ss.pipeline = 'LIVE')) AS live_cnt,
  COUNT(*) FILTER (WHERE ss.signal_source = 'REPLAY') AS replay_cnt
FROM strategy_signals ss, bounds, u
WHERE ss.deleted = FALSE
  AND ss.user_id = u.id
  AND ss.created_at >= bounds.start_utc;

\echo '=== PER STRATEGY TODAY vishnualgo ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo' LIMIT 1)
SELECT
  COALESCE(ss.strategy_name, '(null)') AS strategy_key,
  COUNT(*) AS cnt_today,
  COUNT(*) FILTER (WHERE ss.signal_source = 'LIVE' OR (ss.signal_source IS NULL AND ss.pipeline = 'LIVE')) AS live_cnt,
  COUNT(*) FILTER (WHERE ss.signal_source = 'REPLAY') AS replay_cnt,
  string_agg(DISTINCT ss.symbol, ', ' ORDER BY ss.symbol) FILTER (WHERE ss.symbol IS NOT NULL) AS symbols_sample
FROM strategy_signals ss, bounds, u
WHERE ss.deleted = FALSE AND ss.user_id = u.id AND ss.created_at >= bounds.start_utc
GROUP BY 1 ORDER BY cnt_today DESC;

\echo '=== HOURLY TODAY (IST) all signals ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT
  date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata') AS hour_ist,
  COUNT(*) AS cnt,
  COUNT(*) FILTER (WHERE signal_source = 'LIVE' OR (signal_source IS NULL AND pipeline = 'LIVE')) AS live_cnt
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc
GROUP BY 1 ORDER BY 1;

\echo '=== PIPELINE vs SIGNAL_SOURCE today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT COALESCE(pipeline, '(null)') AS pipeline, COALESCE(signal_source::text, '(null)') AS signal_source, COUNT(*)
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc
GROUP BY 1, 2 ORDER BY 3 DESC;

\echo '=== ACTIVE CATALOG BINDINGS ==='
SELECT sc.strategy_key, ug.group_key, srb.runtime_enabled, srb.max_positions
FROM strategy_runtime_bindings srb
JOIN strategy_catalog sc ON sc.id = srb.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = srb.universe_group_id
WHERE srb.runtime_enabled = TRUE
ORDER BY sc.strategy_key, ug.group_key;
