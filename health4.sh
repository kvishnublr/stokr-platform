#!/bin/bash

echo "=== LTP BATCH ENDPOINT TEST ==="
curl -s "http://localhost:8081/api/market/ltp/batch?symbols=RELIANCE,TCS,INFY" 2>/dev/null | head -200

echo ""
echo "=== STRATEGY UNIVERSE MAPPINGS ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT s.name, ug.group_key, COUNT(us.symbol) as symbols FROM strategy_universe_mappings sm JOIN strategies s ON sm.strategy_id=s.id JOIN universe_groups ug ON sm.group_id=ug.id LEFT JOIN universe_symbols us ON us.group_id=ug.id AND us.enabled=true GROUP BY s.name, ug.group_key;"

echo ""
echo "=== EXECUTION ENGINE (last 500 lines, search for activity) ==="
docker logs stokr-lite-backend --tail 500 2>&1 | grep -iE 'ExecutionEngine|SignalProcessor|processDaily|processIntraday|SchedulerService|marketScan|LIVE.*deployment' | tail -15 || echo "  No execution activity found in last 500 lines"

echo ""
echo "=== ALL BACKEND LOGS (last 50 lines) ==="
docker logs stokr-lite-backend --tail 50 2>&1

echo ""
echo "=== STUCK SIGNALS CHECK ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, created_at::text FROM strategy_signals WHERE status IN ('GENERATED','EXECUTED') ORDER BY created_at DESC;"

echo ""
echo "=== ALL TABLES LIST ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name;"

