#!/bin/bash
set -e
cd /opt/stokr/stokr-platform 2>/dev/null || true

echo "=== GIT ==="
git rev-parse --short HEAD 2>/dev/null || echo "no git"

echo "=== DOCKER ==="
docker ps --format 'table {{.Names}}\t{{.Status}}'

echo "=== API LOG ERRORS (last 2h pattern) ==="
docker logs stokr-api --since 2h 2>&1 | grep -iE 'ERROR|OOM|OutOfMemory|crash' | tail -25 || true

echo "=== MEM ==="
docker stats --no-stream stokr-api stokr-postgres stokr-redis 2>/dev/null || true

echo "=== CADDY API ROUTES ==="
docker exec stokr-caddy cat /etc/caddy/Caddyfile 2>/dev/null || true

PG="docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A"

echo "=== DB COUNTS ==="
$PG -c "SELECT 'orders_today', count(*) FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE;"
$PG -c "SELECT 'orders_7d', count(*) FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE - INTERVAL '7 days';"
$PG -c "SELECT 'blocked_today', count(*) FROM oms_safety_blocked_orders WHERE created_at >= CURRENT_DATE;"
$PG -c "SELECT 'live_signals_today', count(*) FROM strategy_signals WHERE created_at >= CURRENT_DATE AND pipeline = 'LIVE';"
$PG -c "SELECT 'open_positions', count(*) FROM portfolio_positions WHERE deleted=false AND quantity != 0;"
$PG -c "SELECT 'running_instances', count(*) FROM strategy_instances WHERE status='RUNNING';"

echo "=== BLOCKED ORDERS TODAY ==="
$PG -c "SELECT block_code, effective_mode, count(*) FROM oms_safety_blocked_orders WHERE created_at >= CURRENT_DATE GROUP BY 1,2 ORDER BY 3 DESC LIMIT 15;"

echo "=== RECENT ORDERS ==="
$PG -c "SELECT created_at, execution_mode, state, symbol, strategy_key, left(coalesce(reject_reason,''),60) FROM oms_orders WHERE deleted=false ORDER BY created_at DESC LIMIT 12;"

echo "=== SIGNALS WITHOUT ORDERS TODAY LIVE ==="
$PG -c "SELECT count(*) FROM strategy_signals s LEFT JOIN oms_orders o ON o.signal_id = s.id AND o.deleted=false WHERE s.created_at >= CURRENT_DATE AND s.pipeline = 'LIVE' AND o.id IS NULL;"

echo "=== LAST SIGNAL PER STRATEGY ==="
$PG -c "SELECT strategy_name, max(created_at) AS last_signal FROM strategy_signals WHERE created_at >= CURRENT_DATE - INTERVAL '3 days' GROUP BY strategy_name ORDER BY last_signal DESC;"

echo "=== STRATEGY EXEC CONFIG ==="
$PG -c "SELECT strategy_key, execution_mode, enabled, live_armed FROM strategy_execution_config ORDER BY strategy_key;"

echo "=== CANDLE FRESHNESS ==="
$PG -c "SELECT segment, max(bar_time) AS latest FROM market_candles WHERE deleted=false GROUP BY segment ORDER BY segment;" 2>/dev/null || echo "candles query failed"

echo "=== ADMIN API ==="
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))" 2>/dev/null)
H="Authorization: Bearer $TOKEN"
for ep in health ops/status readiness risk-dashboard; do
  echo "--- $ep ---"
  curl -s -H "$H" "http://localhost:8080/api/admin/$ep" 2>/dev/null | python3 -m json.tool 2>/dev/null | head -80 || curl -s -H "$H" "http://localhost:8080/api/admin/$ep" | head -c 2000
  echo
done
echo "--- pipeline ---"
curl -s -H "$H" "http://localhost:8080/api/strategies/runtime-metrics/pipeline-status" | python3 -m json.tool 2>/dev/null | head -100
