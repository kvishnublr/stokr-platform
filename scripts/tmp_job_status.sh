#!/bin/bash
tail -40 /tmp/final_backfill.log
ps aux | grep final_backfill | grep -v grep
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT status, symbol_group, timeframe, processed_symbols, total_symbols, total_candles_fetched, message FROM market_backfill_jobs WHERE deleted=false ORDER BY updated_at DESC LIMIT 3;"
