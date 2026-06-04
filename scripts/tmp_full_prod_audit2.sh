#!/bin/bash
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform"

echo "=== STRATEGY INSTANCES ==="
$PG -t -A -c "SELECT lifecycle_state, count(*) FROM strategy_instances WHERE deleted=false GROUP BY 1;"

echo "=== EXEC CONFIG ==="
$PG -c "SELECT strategy_key, execution_mode, enabled, live_armed, kill_switch FROM strategy_execution_config ORDER BY strategy_key;" 2>&1 | head -40

echo "=== BLOCKED 7D ==="
$PG -c "SELECT date(created_at AT TIME ZONE 'Asia/Kolkata') AS d, block_code, count(*) FROM oms_safety_blocked_orders WHERE created_at >= CURRENT_DATE - INTERVAL '7 days' GROUP BY 1,2 ORDER BY 1 DESC, 3 DESC LIMIT 25;"

echo "=== ORDERS BY DAY 7D ==="
$PG -c "SELECT date(created_at AT TIME ZONE 'Asia/Kolkata') AS d, execution_mode, count(*) FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE - INTERVAL '7 days' GROUP BY 1,2 ORDER BY 1 DESC;"

echo "=== SIGNALS TODAY DETAIL ==="
$PG -c "SELECT strategy_name, symbol, pipeline, outcome_status, created_at AT TIME ZONE 'Asia/Kolkata' AS ist FROM strategy_signals WHERE created_at >= CURRENT_DATE ORDER BY created_at DESC LIMIT 20;"

echo "=== LIVE SIGNALS NO ORDER ==="
$PG -c "SELECT s.strategy_name, s.symbol, s.created_at AT TIME ZONE 'Asia/Kolkata' FROM strategy_signals s LEFT JOIN oms_orders o ON o.signal_id=s.id AND o.deleted=false WHERE s.created_at>=CURRENT_DATE AND s.pipeline='LIVE' AND o.id IS NULL ORDER BY s.created_at DESC LIMIT 15;"

echo "=== CANDLE FRESHNESS marketdata_candles ==="
$PG -c "SELECT timeframe, segment, max(open_time AT TIME ZONE 'Asia/Kolkata') AS latest_ist, count(*) FROM marketdata_candles WHERE deleted=false GROUP BY 1,2 ORDER BY 2,1;" 2>&1 | head -30

echo "=== CDS CANDLES TODAY ==="
$PG -c "SELECT count(*), max(open_time) FROM marketdata_candles WHERE deleted=false AND segment='CDS' AND timeframe='1m' AND open_time >= CURRENT_DATE;" 2>&1

echo "=== CATALOG SCANS TODAY ==="
$PG -c "SELECT scan_outcome, count(*) FROM strategy_catalog_scan_events WHERE created_at >= CURRENT_DATE GROUP BY 1 ORDER BY 2 DESC;" 2>&1 | head -15

echo "=== KILL SWITCH GLOBAL ==="
$PG -c "SELECT key, value FROM system_settings WHERE key LIKE '%kill%' OR key LIKE '%armed%' OR key LIKE '%live%';" 2>&1 | head -20

echo "=== API SNAPSHOTS ==="
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))")
H="Authorization: Bearer $TOKEN"
for ep in "ops/status" readiness "market/feed-health" "broker/platform-feed" "safety/diagnostics"; do
  echo "--- admin/$ep ---"
  curl -s -H "$H" "http://localhost:8080/api/admin/$ep" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',d),indent=2)[:3500])" 2>/dev/null || echo fail
done
curl -s -H "$H" "http://localhost:8080/api/strategies/runtime-metrics/pipeline-status" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',d),indent=2)[:4000])" 2>/dev/null

echo "=== CADDY FULL ==="
docker exec stokr-caddy cat /etc/caddy/Caddyfile 2>/dev/null
