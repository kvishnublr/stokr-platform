#!/bin/bash
set -euo pipefail
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform"

echo "=== CANDLE SUMMARY (180d) ==="
$PG -c "
SELECT timeframe, COUNT(DISTINCT symbol) AS symbols,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date AS min_ist,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date AS max_ist,
  COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted = false
  AND open_time >= NOW() - INTERVAL '180 days'
GROUP BY timeframe
ORDER BY timeframe;
"

echo "=== NIFTY50 PILOT (RELIANCE,TCS,INFY,HDFCBANK,ICICIBANK) ==="
$PG -c "
SELECT symbol, timeframe, COUNT(*) AS bars,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date AS min_ist,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date AS max_ist
FROM marketdata_candles
WHERE deleted = false
  AND open_time >= NOW() - INTERVAL '180 days'
  AND symbol IN ('RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK')
GROUP BY symbol, timeframe
ORDER BY symbol, timeframe;
"

echo "=== GLOBAL 1m RANGE (all symbols) ==="
$PG -c "
SELECT COUNT(DISTINCT symbol) sym_cnt, COUNT(*) bars,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date min_ist,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date max_ist
FROM marketdata_candles
WHERE deleted = false AND timeframe = '1m';
"

echo "=== RECENT BACKFILL JOBS ==="
$PG -c "
SELECT id, status, broker_source, symbol_group, timeframe,
  range_start AT TIME ZONE 'Asia/Kolkata' AS start_ist,
  range_end AT TIME ZONE 'Asia/Kolkata' AS end_ist,
  processed_symbols, total_symbols, total_candles_fetched, message,
  updated_at AT TIME ZONE 'Asia/Kolkata' AS updated_ist
FROM market_backfill_jobs
WHERE deleted = false
ORDER BY updated_at DESC
LIMIT 8;
"

echo "=== ZERODHA SESSION / TRADER ==="
$PG -c "
SELECT u.principal, u.id, ba.vendor_code, ba.account_status,
  fs.id IS NOT NULL AS has_feed_session,
  fs.token_expires_at AT TIME ZONE 'Asia/Kolkata' AS token_exp_ist
FROM users u
LEFT JOIN broker_accounts ba ON ba.user_id = u.id AND ba.deleted = false AND ba.vendor_code ILIKE 'zerodha'
LEFT JOIN feed_sessions fs ON fs.deleted = false AND fs.vendor_code ILIKE 'zerodha'
WHERE u.deleted = false AND (u.principal ILIKE '%vishnu%' OR u.principal = 'vishnualgo' OR u.principal = 'admin')
ORDER BY u.principal;
"
