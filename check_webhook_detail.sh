#!/bin/bash
echo "=== Recent Chartink Webhook Logs (Last 50) ==="
docker logs stokr-api 2>&1 | grep -A 3 -B 1 "chartink.webhook" | tail -50

echo ""
echo "=== Any Errors in Webhook Processing? ==="
docker logs stokr-api 2>&1 | grep -i "error.*webhook\|webhook.*error\|exception" | tail -10

echo ""
echo "=== Latest Signals Created ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, reason, created_at FROM strategy_signals ORDER BY created_at DESC LIMIT 5;"
