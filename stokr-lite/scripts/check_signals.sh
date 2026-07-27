#!/bin/bash
set -e
echo "=== Signal count ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM strategy_signals;"
echo ""
echo "=== Strategy_signals table size ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT pg_size_pretty(pg_total_relation_size('strategy_signals'));"
echo ""
echo "=== API timing for signals ==="
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/signals
echo ""
echo "=== Signals sample ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM strategy_signals;"
echo ""
echo "=== Signals by status ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT status, COUNT(*) FROM strategy_signals GROUP BY status ORDER BY COUNT(*) DESC;"

