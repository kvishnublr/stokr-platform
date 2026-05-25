#!/bin/bash
set -euo pipefail
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A"

echo "=== TODAY (IST) ==="
$PG -c "SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date;"

echo "=== GLOBAL BY TIMEFRAME (180d window) ==="
$PG -c "
SELECT timeframe,
  COUNT(DISTINCT symbol) AS symbols,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date AS start_date,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date AS end_date,
  COUNT(DISTINCT (open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days_with_data,
  COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted = false
  AND open_time >= (NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '180 days'
GROUP BY timeframe
ORDER BY timeframe;
"

echo "=== NIFTY50 PILOT (10 symbols) ==="
$PG -c "
WITH pilot AS (
  SELECT unnest(ARRAY['RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK','SBIN','BHARTIARTL','ITC','KOTAKBANK','LT']) AS symbol
),
today AS (SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date AS d)
SELECT c.symbol, c.timeframe,
  MIN(c.open_time AT TIME ZONE 'Asia/Kolkata')::date AS start_date,
  MAX(c.open_time AT TIME ZONE 'Asia/Kolkata')::date AS end_date,
  COUNT(DISTINCT (c.open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days,
  (SELECT d FROM today) - MIN(c.open_time AT TIME ZONE 'Asia/Kolkata')::date AS calendar_days_span,
  COUNT(*) AS bars
FROM marketdata_candles c
JOIN pilot p ON p.symbol = c.symbol
CROSS JOIN today t
WHERE c.deleted = false
  AND c.open_time >= t.d - INTERVAL '180 days'
  AND c.timeframe IN ('1m','5m','1d')
GROUP BY c.symbol, c.timeframe
ORDER BY c.symbol, c.timeframe;
"

echo "=== NIFTY50 1m SUMMARY (all 50-ish symbols with data) ==="
$PG -c "
SELECT COUNT(DISTINCT symbol) AS symbols_with_1m,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date AS min_start,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date AS max_end,
  ROUND(AVG(days))::int AS avg_trading_days,
  MIN(days) AS min_trading_days,
  MAX(days) AS max_trading_days
FROM (
  SELECT symbol,
    COUNT(DISTINCT (open_time AT TIME ZONE 'Asia/Kolkata')::date) AS days
  FROM marketdata_candles
  WHERE deleted = false AND timeframe = '1m'
    AND open_time >= (NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '180 days'
  GROUP BY symbol
) s;
"

echo "=== BACKFILL JOBS ==="
$PG -c "
SELECT status, symbol_group, timeframe,
  (range_start AT TIME ZONE 'Asia/Kolkata')::date AS start_ist,
  (range_end AT TIME ZONE 'Asia/Kolkata')::date AS end_ist,
  processed_symbols, total_symbols, total_candles_fetched
FROM market_backfill_jobs WHERE deleted = false ORDER BY updated_at DESC LIMIT 5;
"

echo "=== COVERAGE API (sample) ==="
BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])") || exit 0
curl -sf "$BASE/api/admin/market/backfill/coverage" -H "Authorization: Bearer $TOKEN" | python3 <<'PY'
import sys,json
from datetime import datetime, timezone
data=json.load(sys.stdin).get("data",[])
today=datetime.now(timezone.utc)
pilot={"RELIANCE","TCS","INFY","HDFCBANK","ICICIBANK"}
for sym in sorted(pilot):
  for tf in ("1m","5m","1d"):
    rows=[r for r in data if r.get("symbol")==sym and r.get("timeframe")==tf]
    if not rows: continue
    r=rows[0]
    print(sym,tf,r.get("coverageStart"),r.get("coverageEnd"),r.get("completeness"),r.get("gapCount",0))
PY
