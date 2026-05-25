\echo '=== user_id distribution today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT u.username, ss.signal_source, COUNT(*)
FROM strategy_signals ss
LEFT JOIN auth_users u ON u.id = ss.user_id
, bounds
WHERE ss.deleted = FALSE AND ss.created_at >= bounds.start_utc
GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 20;

\echo '=== LIVE only per strategy today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT strategy_name, COUNT(*), string_agg(DISTINCT symbol, ', ' ORDER BY symbol) FILTER (WHERE symbol IS NOT NULL) AS top_symbols
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc AND signal_source = 'LIVE'
GROUP BY 1 ORDER BY 2 DESC;

\echo '=== vishnualgo RUNNING instances ==='
SELECT sd.strategy_key, si.runtime_state, si.symbol, si.execution_mode
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id = si.definition_id
JOIN auth_users u ON u.id = si.user_id
WHERE u.username = 'vishnualgo' AND si.deleted = FALSE;

\echo '=== signals linked to vishnualgo instances today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT sd.strategy_key, ss.signal_source, ss.signal_type, COUNT(*)
FROM strategy_signals ss
JOIN strategy_instances si ON si.id = ss.instance_id
JOIN strategy_definitions sd ON sd.id = si.definition_id
JOIN auth_users u ON u.id = si.user_id
, bounds
WHERE u.username = 'vishnualgo' AND ss.deleted = FALSE AND ss.created_at >= bounds.start_utc
GROUP BY 1, 2, 3 ORDER BY 4 DESC;

\echo '=== first/last signal today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT MIN(created_at) AS first_utc, MAX(created_at) AS last_utc,
  MIN(created_at AT TIME ZONE 'Asia/Kolkata') AS first_ist,
  MAX(created_at AT TIME ZONE 'Asia/Kolkata') AS last_ist
FROM strategy_signals, bounds WHERE deleted = FALSE AND created_at >= bounds.start_utc;

\echo '=== active bindings detail ==='
SELECT sc.strategy_key, ug.group_key, srb.scan_interval_seconds, srb.max_positions
FROM strategy_runtime_bindings srb
JOIN strategy_catalog sc ON sc.id = srb.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = srb.universe_group_id
WHERE srb.runtime_enabled = TRUE ORDER BY 1, 2;

\echo '=== PAPER per strategy ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT strategy_name, COUNT(*) FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc AND signal_source = 'PAPER'
GROUP BY 1 ORDER BY 2 DESC;

\echo '=== REPLAY per strategy ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT strategy_name, COUNT(*) FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc AND signal_source = 'REPLAY'
GROUP BY 1 ORDER BY 2 DESC;

\echo '=== catalog vs poll: pipeline breakdown LIVE ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT pipeline, COUNT(*) FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc AND signal_source = 'LIVE'
GROUP BY 1;
