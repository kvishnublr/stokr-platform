#!/bin/bash
set -e

echo "=== System Resources ==="
free -h
echo ""
echo "=== CPU Load ==="
uptime
echo ""
echo "=== Java Process ==="
ps aux | grep java | grep -v grep
echo ""
echo "=== Java Heap Usage ==="
jstat -gcutil $(pgrep -f stokr-lite) 2>/dev/null || echo "jstat not available"
echo ""
echo "=== Disk I/O ==="
iostat -x 1 1 2>/dev/null || echo "iostat not available"
echo ""
echo "=== PostgreSQL connections ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT count(*) FROM pg_stat_activity WHERE datname='stokr_lite';"
echo ""
echo "=== Recent slow queries ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT calls, mean_exec_time, total_exec_time, query FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 5;" 2>/dev/null || echo "pg_stat_statements not available"
echo ""
echo "=== Table sizes ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT schemaname, relname, pg_size_pretty(pg_total_relation_size(schemaname||'.'||relname)) as size FROM pg_stat_user_tables ORDER BY pg_total_relation_size(schemaname||'.'||relname) DESC LIMIT 10;"
echo ""
echo "=== Last 20 log entries (excluding anomaly) ==="
tail -50 /opt/stokr/stokr-lite.log | grep -v Anomaly | grep -v AnomalyDetection | tail -20
echo ""
echo "=== Frontend check ==="
time curl -s -o /dev/null -w "HTTP %{http_code} - Total: %{time_total}s\n" http://localhost:8081/
echo ""
echo "=== Backend API check ==="
time curl -s -o /dev/null -w "HTTP %{http_code} - Total: %{time_total}s\n" http://localhost:8081/api/strategies
