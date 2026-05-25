#!/bin/bash
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol,
  COUNT(DISTINCT (open_time AT TIME ZONE 'Asia/Kolkata')::date) AS trading_days,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date AS start_date,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date AS end_date,
  COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted = false AND timeframe = '1m'
  AND open_time >= (NOW() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '180 days'
GROUP BY symbol
ORDER BY trading_days DESC
LIMIT 15;
"
