#!/bin/bash
set -e

echo "=== Step 1: Delete existing daily candles ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "DELETE FROM candle_data WHERE timeframe = 'daily';"

echo "=== Step 2: Load CSV into DB ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "COPY candle_data(symbol, timeframe, timestamp, open, high, low, close, volume) FROM '/tmp/daily_candles_3yr.csv' WITH DELIMITER '|';"

echo "=== Step 3: Verify ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT MIN(timestamp)::date, MAX(timestamp)::date, COUNT(DISTINCT symbol), COUNT(*) FROM candle_data WHERE timeframe='daily';"
