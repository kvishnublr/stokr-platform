#!/bin/bash
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT id,status,symbol_group,timeframe,processed_symbols,total_symbols,total_candles_fetched,message FROM market_backfill_jobs WHERE deleted=false ORDER BY updated_at DESC LIMIT 5;"
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol) syms,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date min_d,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date max_d,
  COUNT(*) bars
FROM marketdata_candles WHERE deleted=false AND timeframe IN ('1m','5m','1d')
  AND open_time >= NOW()-INTERVAL '180 days'
GROUP BY 1 ORDER BY 1;
"
