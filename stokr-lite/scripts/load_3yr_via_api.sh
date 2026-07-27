#!/bin/bash
# Use the app's existing candle fetch service to load 3-year daily data
# The Java CandleFetchService handles Zerodha auth internally

# First, check what date range we need
echo "=== Current daily data range ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT MIN(timestamp)::date as earliest, MAX(timestamp)::date as latest, COUNT(*) FROM candle_data WHERE timeframe='daily';"

echo ""
echo "=== Triggering data load via API for 3-year range ==="

# Use the load-data endpoint to fetch 3 years
curl -s -X POST 'http://localhost:8081/api/backtest/load-data?dateStart=2023-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&timeframe=daily&universe=NIFTY_50' | python3 -m json.tool

echo ""
echo "=== Also try admin backfill endpoint ==="
curl -s -X POST 'http://localhost:8081/api/admin/candles/backfill?dateStart=2023-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&universe=NIFTY_50&timeframe=daily' | python3 -m json.tool

