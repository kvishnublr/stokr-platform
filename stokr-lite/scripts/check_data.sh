#!/bin/bash
# Check actual data coverage in DB
echo "=== 1-MIN CANDLE DATA ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "
SELECT 
  timeframe,
  MIN(timestamp) as earliest,
  MAX(timestamp) as latest,
  COUNT(*) as total_candles,
  COUNT(DISTINCT symbol) as symbols
FROM candle_data 
GROUP BY timeframe
ORDER BY timeframe;
"

echo ""
echo "=== DAILY CANDLES PER SYMBOL (sample) ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "
SELECT 
  symbol,
  MIN(timestamp) as earliest,
  MAX(timestamp) as latest,
  COUNT(*) as days
FROM candle_data 
WHERE timeframe = 'daily'
GROUP BY symbol
ORDER BY symbol
LIMIT 10;
"

echo ""
echo "=== DAILY CANDLE DATE RANGE ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "
SELECT 
  MIN(timestamp) as earliest_daily,
  MAX(timestamp) as latest_daily,
  COUNT(*) as total_daily_candles,
  COUNT(DISTINCT symbol) as symbols_with_daily
FROM candle_data 
WHERE timeframe = 'daily';
"

