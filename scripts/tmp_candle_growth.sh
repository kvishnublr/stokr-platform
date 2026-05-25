#!/bin/bash
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT COUNT(*) total_1m, COUNT(DISTINCT symbol) syms,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date min_d,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date max_d
FROM marketdata_candles WHERE deleted=false AND timeframe='1m';
"
docker logs stokr-api 2>&1 | tail -15
