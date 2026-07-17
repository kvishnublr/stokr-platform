#!/bin/bash
set -e

echo "========================================="
echo "  COMPREHENSIVE HEALTH CHECK"
echo "========================================="

echo ""
echo "[1] DOCKER CONTAINERS"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

echo ""
echo "[2] API ENDPOINTS"
for ep in strategies deployments signals orders positions market/ltp/batch; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/$ep 2>/dev/null)
  echo "  /api/$ep: $code"
done

echo ""
echo "[3] NGINX / FRONTEND"
code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost 2>/dev/null)
echo "  localhost: $code"

echo ""
echo "[4] DATABASE TABLES"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "
SELECT '  strategies: ' || COUNT(*) FROM strategies
UNION ALL SELECT '  deployments: ' || COUNT(*) FROM deployments
UNION ALL SELECT '  signals: ' || COUNT(*) FROM strategy_signals
UNION ALL SELECT '  orders: ' || COUNT(*) FROM orders
UNION ALL SELECT '  positions: ' || COUNT(*) FROM positions
UNION ALL SELECT '  candle_data: ' || COUNT(*) FROM candle_data
UNION ALL SELECT '  broker_accounts: ' || COUNT(*) FROM broker_accounts
UNION ALL SELECT '  universe_groups: ' || COUNT(*) FROM universe_groups
UNION ALL SELECT '  universe_symbols: ' || COUNT(*) FROM universe_symbols;
"

echo ""
echo "[5] STRATEGIES"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, name, timeframe, enabled FROM strategies ORDER BY id;"

echo ""
echo "[6] DEPLOYMENTS"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT d.id, s.name, d.status, d.capital, d.broker_account_id, d.mode FROM deployments d JOIN strategies s ON d.strategy_id=s.id ORDER BY d.id;"

echo ""
echo "[7] BROKER ACCOUNTS"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, broker_name, client_id, status, auto_reconnect, token_expiry::text FROM broker_accounts;"

echo ""
echo "[8] CANDLE DATA RANGES"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT timeframe, COUNT(*) as cnt, MIN(timestamp)::date as start_date, MAX(timestamp)::date as end_date FROM candle_data GROUP BY timeframe ORDER BY timeframe;"

echo ""
echo "[9] RECENT SIGNALS"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, signal_type, status, deployment_id, created_at::text FROM strategy_signals ORDER BY created_at DESC LIMIT 15;"

echo ""
echo "[10] SIGNAL STATUS DISTRIBUTION"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT status, COUNT(*) FROM strategy_signals GROUP BY status ORDER BY COUNT(*) DESC;"

echo ""
echo "[11] OPEN POSITIONS"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, quantity, entry_price, unrealized_pnl FROM positions WHERE status='OPEN' ORDER BY symbol;"
echo "  (if empty = no open positions)"

echo ""
echo "[12] RECENT ORDERS"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, order_type, status, quantity, price, created_at::text FROM orders ORDER BY created_at DESC LIMIT 10;"

echo ""
echo "[13] INTEGRITY CHECKS"
echo -n "  Orphan deployments (no broker): "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM deployments WHERE broker_account_id IS NULL;"
echo -n "  Orphan signals (no deployment): "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM strategy_signals WHERE deployment_id IS NULL;"
echo -n "  EXECUTED signals with no order: "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM strategy_signals s WHERE s.status='EXECUTED' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.signal_id=s.id);"
echo -n "  Open positions with NULL broker: "
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT COUNT(*) FROM positions WHERE status='OPEN' AND deployment_id IS NULL;"

echo ""
echo "[14] TOKEN VALIDITY"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, status, token_expiry::text, CASE WHEN token_expiry > NOW() AT TIME ZONE 'UTC' THEN 'VALID' ELSE 'EXPIRED' END as token_status FROM broker_accounts;"

echo ""
echo "[15] BACKEND ERRORS (last 50 log lines)"
docker logs stokr-lite-backend --tail 50 2>&1 | grep -iE 'ERROR|Exception|FAIL' | tail -10 || echo "  No errors found"

echo ""
echo "[16] EXECUTION ENGINE ACTIVITY"
docker logs stokr-lite-backend --tail 100 2>&1 | grep -iE 'process|scan|signal|entry|exit|reconcil|scheduler|DEPLOYMENT' | tail -10 || echo "  (no activity found)"

echo ""
echo "[17] CRONTAB"
crontab -l 2>&1 || echo "  (no crontab)"

echo ""
echo "[18] DISK SPACE"
df -h / | tail -1

echo ""
echo "[19] DATABASE SIZE"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT pg_size_pretty(pg_database_size('stokr_lite'));"

echo ""
echo "[20] STRATEGY-UNIVERSE MAPPINGS"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "
SELECT s.name, ug.group_key, COUNT(us.symbol)
FROM strategy_universe_mappings sum
JOIN strategies s ON sum.strategy_id=s.id
JOIN universe_groups ug ON sum.group_id=ug.id
LEFT JOIN universe_symbols us ON us.group_id=ug.id
GROUP BY s.name, ug.group_key;
" 2>/dev/null || echo "  (no mappings table)"

echo ""
echo "========================================="
echo "  CHECK COMPLETE"
echo "========================================="
