#!/bin/bash
echo "=== Application Logs (Last 100 lines, signal-related) ==="
docker logs stokr-api 2>&1 | grep -i "signal\|scan\|deployment\|strategy\|error" | tail -50

echo ""
echo "=== Check Strategies Table ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, name, is_enabled, created_at FROM strategies;"

echo ""
echo "=== Check Signals by Status ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT status, COUNT(*) as count FROM strategy_signals GROUP BY status;"

echo ""
echo "=== Check Signals for Today by Hour ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT EXTRACT(HOUR FROM created_at) as hour, COUNT(*) as signals FROM strategy_signals WHERE created_at > CURRENT_DATE GROUP BY EXTRACT(HOUR FROM created_at) ORDER BY hour;"

echo ""
echo "=== Current Time (IST) ==="
docker exec stokr-api date '+%Y-%m-%d %H:%M:%S IST'
