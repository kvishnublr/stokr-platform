#!/bin/bash
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform"
$PG -c "SELECT COUNT(*) AS extra_symbols_not_in_nifty100 FROM (
  SELECT DISTINCT c.symbol FROM marketdata_candles c
  WHERE c.deleted=false AND c.timeframe='1m'
    AND c.symbol NOT IN (
      SELECT s.symbol FROM strategy_universe_groups g
      JOIN strategy_universe_symbols s ON s.group_id=g.id AND s.enabled
      WHERE g.group_key='NIFTY_100')
) x;"

$PG -c "SELECT c.symbol, COUNT(*) bars,
  MIN((open_time AT TIME ZONE 'Asia/Kolkata')::date) min_d,
  MAX((open_time AT TIME ZONE 'Asia/Kolkata')::date) max_d
FROM marketdata_candles c
WHERE c.deleted=false AND c.timeframe='1m'
  AND c.symbol NOT IN (
    SELECT s.symbol FROM strategy_universe_groups g
    JOIN strategy_universe_symbols s ON s.group_id=g.id AND s.enabled
    WHERE g.group_key='NIFTY_100')
GROUP BY c.symbol ORDER BY c.symbol;"

echo "=== NIFTY_50 thin <6 days (14d window) ==="
$PG -c "
WITH bounds AS (
  SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date AS today_ist,
         ((NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '14 days')::date AS window_start
),
expected AS (
  SELECT s.symbol FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id = g.id AND s.enabled = true
  WHERE g.group_key = 'NIFTY_50' AND g.enabled = true
),
recent AS (
  SELECT c.symbol,
         COUNT(DISTINCT (c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days,
         COUNT(*) AS bars
  FROM marketdata_candles c CROSS JOIN bounds b
  WHERE c.deleted = false AND c.timeframe = '1m'
    AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date BETWEEN b.window_start AND b.today_ist
  GROUP BY c.symbol
)
SELECT e.symbol, COALESCE(r.trading_days,0) td, COALESCE(r.bars,0) bars
FROM expected e LEFT JOIN recent r ON r.symbol=e.symbol
WHERE COALESCE(r.trading_days,0) < 6 ORDER BY 2,1;"

echo "=== Today last bars sample ==="
$PG -c "SELECT symbol, MAX(open_time AT TIME ZONE 'Asia/Kolkata') last_ist
FROM marketdata_candles WHERE deleted=false AND timeframe='1m'
 AND (open_time AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date
GROUP BY symbol ORDER BY last_ist DESC LIMIT 5;"

echo "=== stokr-api market logs ==="
docker logs stokr-api --tail 80 2>&1 | grep -iE 'market|candle|websocket|ingest|zerodha|tick' | tail -25
