#!/bin/bash
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform"

echo "=== 1m: trading days distribution (180d lookback) ==="
$PG -c "
SELECT
  CASE
    WHEN days >= 50 THEN '50+ days (near full Zerodha 1m window)'
    WHEN days >= 30 THEN '30-49 days'
    WHEN days >= 7 THEN '7-29 days'
    ELSE '<7 days'
  END AS bucket,
  COUNT(*) AS symbol_count
FROM (
  SELECT symbol,
    COUNT(DISTINCT (open_time AT TIME ZONE 'Asia/Kolkata')::date) AS days
  FROM marketdata_candles
  WHERE deleted = false AND timeframe = '1m'
    AND open_time >= (NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '180 days'
  GROUP BY symbol
) s
GROUP BY 1 ORDER BY 1;
"

echo "=== Symbols with 50+ trading days (1m) ==="
$PG -c "
SELECT symbol,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date AS start_date,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date AS end_date,
  COUNT(DISTINCT (open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days,
  COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted = false AND timeframe = '1m'
  AND open_time >= (NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '180 days'
GROUP BY symbol
HAVING COUNT(DISTINCT (open_time AT TIME ZONE 'Asia/Kolkata')::date) >= 50
ORDER BY trading_days DESC, symbol
LIMIT 60;
"

echo "=== 5m and 1d existence ==="
$PG -c "
SELECT timeframe, COUNT(DISTINCT symbol), MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date, COUNT(*)
FROM marketdata_candles WHERE deleted=false AND timeframe IN ('5m','1d') GROUP BY 1;
"

echo "=== Days from today to earliest 1m (universe) ==="
$PG -c "
SELECT
  ((NOW() AT TIME ZONE 'Asia/Kolkata')::date - MIN((open_time AT TIME ZONE 'Asia/Kolkata')::date)) AS calendar_days_back,
  COUNT(DISTINCT symbol) AS symbols
FROM marketdata_candles
WHERE deleted = false AND timeframe = '1m'
  AND open_time >= (NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '180 days';
"
