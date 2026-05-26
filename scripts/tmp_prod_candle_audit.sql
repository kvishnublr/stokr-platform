\set ON_ERROR_STOP on
\pset pager off

\echo '=== META: today IST ==='
SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date AS today_ist;

\echo '=== TOTAL CANDLES (non-deleted) ==='
SELECT COUNT(*) AS total_rows FROM marketdata_candles WHERE deleted = false;

\echo '=== BY TIMEFRAME ==='
SELECT timeframe,
       COUNT(DISTINCT symbol) AS symbols,
       MIN(open_time AT TIME ZONE 'Asia/Kolkata') AS min_open_ist,
       MAX(open_time AT TIME ZONE 'Asia/Kolkata') AS max_open_ist,
       COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted = false
GROUP BY timeframe
ORDER BY timeframe;

\echo '=== DISTINCT SYMBOLS WITH 1m DATA (all time) ==='
SELECT COUNT(DISTINCT symbol) AS symbols_1m
FROM marketdata_candles
WHERE deleted = false AND timeframe = '1m';

\echo '=== UNIVERSE COUNTS (strategy_universe_symbols) ==='
SELECT g.group_key, COUNT(*) AS expected_symbols
FROM strategy_universe_groups g
JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
WHERE g.group_key IN ('NIFTY_50', 'NIFTY_100') AND g.enabled = true
GROUP BY g.group_key
ORDER BY g.group_key;

\echo '=== COVERAGE vs NIFTY_50 (1m, any history) ==='
WITH expected AS (
  SELECT s.symbol
  FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
  WHERE g.group_key = 'NIFTY_50' AND g.enabled = true
),
have AS (
  SELECT DISTINCT symbol FROM marketdata_candles WHERE deleted = false AND timeframe = '1m'
)
SELECT
  (SELECT COUNT(*) FROM expected) AS expected_nifty50,
  (SELECT COUNT(*) FROM expected e JOIN have h ON h.symbol = e.symbol) AS with_1m,
  (SELECT COUNT(*) FROM expected e LEFT JOIN have h ON h.symbol = e.symbol WHERE h.symbol IS NULL) AS missing_1m;

\echo '=== COVERAGE vs NIFTY_100 (1m, any history) ==='
WITH expected AS (
  SELECT s.symbol
  FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
  WHERE g.group_key = 'NIFTY_100' AND g.enabled = true
),
have AS (
  SELECT DISTINCT symbol FROM marketdata_candles WHERE deleted = false AND timeframe = '1m'
)
SELECT
  (SELECT COUNT(*) FROM expected) AS expected_nifty100,
  (SELECT COUNT(*) FROM expected e JOIN have h ON h.symbol = e.symbol) AS with_1m,
  (SELECT COUNT(*) FROM expected e LEFT JOIN have h ON h.symbol = e.symbol WHERE h.symbol IS NULL) AS missing_1m;

\echo '=== LAST ~7 TRADING DAYS WINDOW (IST calendar -14d) per symbol in NIFTY_100 ==='
WITH bounds AS (
  SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date AS today_ist,
         ((NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '14 days')::date AS window_start
),
expected AS (
  SELECT s.symbol
  FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
  WHERE g.group_key = 'NIFTY_100' AND g.enabled = true
),
recent AS (
  SELECT c.symbol,
         COUNT(DISTINCT (c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days,
         COUNT(*) AS bars,
         MIN((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS min_d,
         MAX((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS max_d
  FROM marketdata_candles c
  CROSS JOIN bounds b
  WHERE c.deleted = false AND c.timeframe = '1m'
    AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date >= b.window_start
    AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date <= b.today_ist
  GROUP BY c.symbol
)
SELECT
  COUNT(*) FILTER (WHERE r.symbol IS NULL) AS zero_rows_in_window,
  COUNT(*) FILTER (WHERE COALESCE(r.trading_days,0) < 6) AS thin_lt6_days,
  COUNT(*) FILTER (WHERE COALESCE(r.trading_days,0) >= 6) AS ok_ge6_days
FROM expected e
LEFT JOIN recent r ON r.symbol = e.symbol;

\echo '=== MISSING 1m (NIFTY_50) ==='
WITH expected AS (
  SELECT s.symbol
  FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
  WHERE g.group_key = 'NIFTY_50' AND g.enabled = true
)
SELECT e.symbol
FROM expected e
WHERE NOT EXISTS (
  SELECT 1 FROM marketdata_candles c
  WHERE c.deleted = false AND c.timeframe = '1m' AND c.symbol = e.symbol
)
ORDER BY e.symbol;

\echo '=== THIN in window (<6 trading days, NIFTY_100) ==='
WITH bounds AS (
  SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date AS today_ist,
         ((NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '14 days')::date AS window_start
),
expected AS (
  SELECT s.symbol
  FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
  WHERE g.group_key = 'NIFTY_100' AND g.enabled = true
),
recent AS (
  SELECT c.symbol,
         COUNT(DISTINCT (c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days,
         COUNT(*) AS bars,
         MIN((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS min_d,
         MAX((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS max_d
  FROM marketdata_candles c
  CROSS JOIN bounds b
  WHERE c.deleted = false AND c.timeframe = '1m'
    AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date >= b.window_start
    AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date <= b.today_ist
  GROUP BY c.symbol
)
SELECT e.symbol, COALESCE(r.trading_days,0) AS trading_days, COALESCE(r.bars,0) AS bars, r.min_d, r.max_d
FROM expected e
LEFT JOIN recent r ON r.symbol = e.symbol
WHERE COALESCE(r.trading_days,0) < 6
ORDER BY trading_days, e.symbol;

\echo '=== SAMPLE: RELIANCE, INFY, TATAMOTORS (1m) ==='
WITH bounds AS (
  SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date AS today_ist,
         ((NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '14 days')::date AS window_start
),
pilot(sym) AS (VALUES ('RELIANCE'),('INFY'),('TATAMOTORS'))
SELECT p.sym,
       COUNT(*) FILTER (WHERE c.id IS NOT NULL) AS bars_all_time,
       MIN((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS min_date_ist,
       MAX((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS max_date_ist,
       COUNT(DISTINCT (c.open_time AT TIME ZONE 'Asia/Kolkata')::date)
         FILTER (WHERE (c.open_time AT TIME ZONE 'Asia/Kolkata')::date >= b.window_start
                   AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date <= b.today_ist) AS trading_days_14d,
       COUNT(*) FILTER (WHERE (c.open_time AT TIME ZONE 'Asia/Kolkata')::date >= b.window_start
                          AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date <= b.today_ist) AS bars_14d
FROM pilot p
CROSS JOIN bounds b
LEFT JOIN marketdata_candles c ON c.symbol = p.sym AND c.timeframe = '1m' AND c.deleted = false
GROUP BY p.sym, b.window_start, b.today_ist
ORDER BY p.sym;

\echo '=== BACKFILL JOBS (latest 10) ==='
SELECT status, symbol_group, timeframe,
       (range_start AT TIME ZONE 'Asia/Kolkata')::date AS start_ist,
       (range_end AT TIME ZONE 'Asia/Kolkata')::date AS end_ist,
       processed_symbols, total_symbols, total_candles_fetched, failure_count,
       updated_at AT TIME ZONE 'Asia/Kolkata' AS updated_ist
FROM market_backfill_jobs
WHERE deleted = false
ORDER BY updated_at DESC
LIMIT 10;

\echo '=== RECENT 1m INGESTION (max open_time per day, last 5 days, top symbols count) ==='
SELECT (open_time AT TIME ZONE 'Asia/Kolkata')::date AS d,
       COUNT(DISTINCT symbol) AS symbols_with_bars,
       COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted = false AND timeframe = '1m'
  AND (open_time AT TIME ZONE 'Asia/Kolkata')::date >= ((NOW() AT TIME ZONE 'Asia/Kolkata')::date - 5)
GROUP BY 1
ORDER BY 1 DESC;

