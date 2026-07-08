#!/bin/bash
set -e
echo "=== Java processes ==="
ps aux | grep java | grep -v grep
echo ""
echo "=== Frontend timing ==="
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s\n' http://localhost:8081/
echo ""
echo "=== API timing ==="
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s\n' http://localhost:8081/api/strategies
echo ""
echo "=== Table sizes ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) as size FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;"
echo ""
echo "=== DB row counts ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT 'candle_data' as tbl, COUNT(*) FROM candle_data UNION ALL SELECT 'trades', COUNT(*) FROM trades UNION ALL SELECT 'strategies', COUNT(*) FROM strategies UNION ALL SELECT 'deployments', COUNT(*) FROM deployments UNION ALL SELECT 'trade_logs', COUNT(*) FROM trade_logs;"
echo ""
echo "=== Zerodha env ==="
grep ZERODHA /opt/stokr/stokr-lite.env
echo ""
echo "=== Application logs (last 30 lines, errors only) ==="
tail -200 /opt/stokr/stokr-lite.log | grep -i 'error\|exception\|slow\|timeout\|WARN' | grep -v Anomaly | grep -v AnomalyDetection | tail -15
