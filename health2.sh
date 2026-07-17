#!/bin/bash

echo "=== RECENT SIGNALS ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, deployment_id, created_at::text FROM strategy_signals ORDER BY created_at DESC LIMIT 15;"

echo ""
echo "=== SIGNAL STATUS DISTRIBUTION ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT status, COUNT(*) FROM strategy_signals GROUP BY status ORDER BY COUNT(*) DESC;"

echo ""
echo "=== OPEN POSITIONS ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, quantity, entry_price, unrealized_pnl FROM positions WHERE status='OPEN' ORDER BY symbol;"

echo ""
echo "=== RECENT ORDERS ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, order_type, status, quantity, price, created_at::text FROM orders ORDER BY created_at DESC LIMIT 10;"

echo ""
echo "=== TOKEN VALIDITY ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, status, token_expiry::text, CASE WHEN token_expiry > NOW() AT TIME ZONE 'UTC' THEN 'VALID' ELSE 'EXPIRED' END as token_status FROM broker_accounts;"

echo ""
echo "=== INTEGRITY CHECKS ==="
echo -n "  Orphan deployments (no broker): "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM deployments WHERE broker_account_id IS NULL;"
echo -n "  Orphan signals (no deployment): "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM strategy_signals WHERE deployment_id IS NULL;"
echo -n "  EXECUTED signals with no order: "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM strategy_signals s WHERE s.status='EXECUTED' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.signal_id=s.id);"
echo -n "  Open positions no signal: "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM positions WHERE status='OPEN' AND deployment_id IS NULL;"

echo ""
echo "=== BACKEND ERRORS (last 100 lines) ==="
docker logs stokr-lite-backend --tail 100 2>&1 | grep -iE 'ERROR|Exception|FAIL' | tail -15 || echo "  No errors"

echo ""
echo "=== EXECUTION ENGINE ACTIVITY (last 200 lines) ==="
docker logs stokr-lite-backend --tail 200 2>&1 | grep -iE 'process|scan|signal|entry|exit|reconcil|scheduler|DEPLOYMENT|ExecutionEngine|SignalProcessor' | tail -15 || echo "  No activity"

echo ""
echo "=== MARKET LTP BATCH ERROR ==="
docker logs stokr-lite-backend --tail 200 2>&1 | grep -iA3 "ltp/batch\|MarketData\|ltp.*error\|batch.*error" | tail -15 || echo "  No related errors"

echo ""
echo "=== CRONTAB ==="
crontab -l 2>&1

echo ""
echo "=== DISK SPACE ==="
df -h / | tail -1

echo ""
echo "=== DB SIZE ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT pg_size_pretty(pg_database_size('stokr_lite'));"

echo ""
echo "=== STRATEGY-UNIVERSE MAPPINGS ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "
SELECT s.name, ug.group_key, COUNT(us.symbol)
FROM strategy_universe_mappings sum2
JOIN strategies s ON sum2.strategy_id=s.id
JOIN universe_groups ug ON sum2.group_id=ug.id
LEFT JOIN universe_symbols us ON us.group_id=ug.id
GROUP BY s.name, ug.group_key;
" 2>/dev/null || echo "  (table not found)"
