#!/bin/bash
echo "=== final_backfill.log ==="
tail -30 /tmp/final_backfill.log 2>/dev/null || echo missing
echo "=== stable_backfill.log ==="
tail -30 /tmp/stable_backfill.log 2>/dev/null || echo missing
echo "=== jobs ==="
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT status,symbol_group,timeframe,processed_symbols,total_symbols,total_candles_fetched,message FROM market_backfill_jobs WHERE deleted=false ORDER BY updated_at DESC LIMIT 5;"
