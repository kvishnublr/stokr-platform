#!/bin/bash
PG="docker exec stokr-postgres psql -U postgres -d stokr_platform"

echo "=== INSTANCES runtime_state ==="
$PG -c "SELECT runtime_state, count(*) FROM strategy_instances WHERE deleted=false GROUP BY 1;"

echo "=== EXEC CONFIGS (catalog) ==="
$PG -c "SELECT strategy_key, execution_mode, enabled, live_armed FROM strategy_execution_configs WHERE user_id IS NULL ORDER BY strategy_key;"

echo "=== ALL 13 STRATEGIES ==="
$PG -c "SELECT strategy_key, enabled FROM strategy_definitions WHERE deleted=false ORDER BY strategy_key;"

echo "=== SIGNAL TYPE today LIVE no order ==="
$PG -c "SELECT s.strategy_name, s.symbol, s.signal_type, s.outcome_status, s.side FROM strategy_signals s LEFT JOIN oms_orders o ON o.signal_id=s.id AND o.deleted=false WHERE s.created_at>=CURRENT_DATE AND s.pipeline='LIVE' AND o.id IS NULL ORDER BY s.created_at DESC;"

echo "=== TRADES OPENED TODAY ==="
$PG -c "SELECT count(*) FROM oms_orders WHERE deleted=false AND created_at>=CURRENT_DATE AND state IN ('FILLED','PARTIALLY_FILLED','OPEN');" 2>&1

echo "=== CANDLE max by symbol group sample ==="
$PG -c "SELECT timeframe, max(open_time AT TIME ZONE 'Asia/Kolkata') AS latest FROM marketdata_candles WHERE deleted=false GROUP BY timeframe;"

echo "=== CDS symbols candles ==="
$PG -c "SELECT symbol, max(open_time AT TIME ZONE 'Asia/Kolkata') AS latest FROM marketdata_candles WHERE deleted=false AND symbol LIKE '%USD%' OR symbol IN ('USDINR','EURINR','GBPINR','JPYINR') GROUP BY symbol ORDER BY latest DESC LIMIT 10;" 2>&1

echo "=== SCAN BLOCKS (risk_events today) ==="
$PG -c "SELECT reason_code, count(*) FROM risk_events WHERE created_at>=CURRENT_DATE GROUP BY 1 ORDER BY 2 DESC LIMIT 15;" 2>&1

echo "=== API endpoints ==="
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))")
H="Authorization: Bearer $TOKEN"
for ep in operations/snapshot broker-infrastructure/zerodha risk-dashboard oms/summary diagnostics/signals "oms/position-reconciliation"; do
  echo "--- $ep ---"
  curl -s -H "$H" "http://localhost:8080/api/admin/$ep" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',d),indent=2)[:4500])" 2>/dev/null
  echo
done

echo "=== REDIS LIVE ARMED ==="
docker exec stokr-redis redis-cli GET stokr:live:armed 2>/dev/null
docker exec stokr-redis redis-cli GET stokr:kill:switch 2>/dev/null
docker exec stokr-redis redis-cli KEYS 'stokr:*' 2>/dev/null | head -20

echo "=== UI NGINX API PROXY ==="
docker exec stokr-ui cat /etc/nginx/conf.d/default.conf 2>/dev/null | head -40
