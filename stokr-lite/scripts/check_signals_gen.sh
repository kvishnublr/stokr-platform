#!/bin/bash
# Check why no signals are generated
echo "=== Latest scan cycles ==="
tail -200 /opt/stokr/stokr-lite.log | grep "Scan cycle"

echo ""
echo "=== Active deployments ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT ds.id, ds.strategy_id, s.name, ds.status, ds.mode, ds.capital FROM deployments ds JOIN strategies s ON ds.strategy_id = s.id ORDER BY ds.id;"

echo ""
echo "=== Deployment statuses ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT DISTINCT status FROM deployments;"

echo ""
echo "=== Recent signals (last 7 days) ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, strategy_id, status, created_at FROM strategy_signals WHERE created_at > NOW() - INTERVAL '7 days' ORDER BY id DESC LIMIT 10;"

echo ""
echo "=== Market open check ==="
curl -s http://localhost:8081/api/market/status --max-time 5

echo ""
echo "=== Any errors in last 100 lines? ==="
tail -100 /opt/stokr/stokr-lite.log | grep -iE 'error|exception|warn' | head -10

