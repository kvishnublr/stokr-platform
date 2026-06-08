#!/bin/bash
echo "=== REPLAY ENV ==="
docker exec stokr-api env | grep -iE 'REPLAY|SEED|SYNTHETIC' 2>/dev/null
echo "=== ORDERS LAST 10 MIN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT execution_mode, state, count(*) FROM oms_orders WHERE created_at > NOW() - INTERVAL '10 minutes' GROUP BY execution_mode, state ORDER BY execution_mode, state;"
echo "=== EXIT MONITOR TAIL ==="
tail -3 /var/log/stokr-exit-monitor.log
