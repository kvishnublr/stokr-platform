#!/bin/bash
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform"
echo "=== LIVE signals no order - signal_type ==="
$PG -c "SELECT s.strategy_name, s.symbol, s.signal_type, s.outcome_status FROM strategy_signals s LEFT JOIN oms_orders o ON o.signal_id=s.id AND o.deleted=false WHERE s.created_at>=CURRENT_DATE AND s.pipeline='LIVE' AND o.id IS NULL ORDER BY s.created_at DESC;"

echo "=== EXEC CONFIGS ==="
$PG -c "SELECT strategy_key, execution_mode, enabled FROM strategy_execution_configs WHERE user_id IS NULL ORDER BY strategy_key;"

echo "=== LAST SIGNAL PER STRATEGY 7d ==="
$PG -c "SELECT strategy_name, max(created_at AT TIME ZONE 'Asia/Kolkata') AS last_ist FROM strategy_signals WHERE created_at >= CURRENT_DATE - INTERVAL '7 days' GROUP BY strategy_name ORDER BY last_ist DESC NULLS LAST;"

echo "=== INDEX/SECTOR signals blocked today ==="
$PG -c "SELECT strategy_name, outcome_status, count(*) FROM strategy_signals WHERE created_at>=CURRENT_DATE AND strategy_name IN ('INDEX_HUNT','SECTOR_LAGGARD') GROUP BY 1,2;"

echo "=== OMS endpoints ==="
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
H="Authorization: Bearer $TOKEN"
curl -s -H "$H" http://localhost:8080/api/admin/oms/summary | python3 -m json.tool
curl -s -H "$H" http://localhost:8080/api/admin/oms/reject-reasons | python3 -m json.tool
curl -s -H "$H" http://localhost:8080/api/admin/oms/stats | python3 -m json.tool
