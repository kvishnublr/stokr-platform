#!/bin/bash

echo "=== LAST 1000 LINES OF BACKEND LOGS (grep for key activity) ==="
docker logs stokr-lite-backend --tail 1000 2>&1 | grep -iE 'ExecutionEngine|SignalProcessor|SchedulerService|marketScan|processDaily|processIntraday|scan.*cycle|LIVE|deployment|entry|exit|reconcil' | tail -30

echo ""
echo "=== TODAY'S LOGS ONLY ==="
docker logs stokr-lite-backend 2>&1 | grep "2026-07-13" | grep -viE 'SecurityFilter|authentication|AuthenticationFilter|o\.s\.s\.|session\.InvalidSession' | tail -30

echo ""
echo "=== SCHEDULER SERVICE ==="
docker logs stokr-lite-backend --tail 2000 2>&1 | grep -iE 'Scheduled|cron|trigger|market|scan|schedule' | tail -15

echo ""
echo "=== ERRORS TODAY ==="
docker logs stokr-lite-backend --tail 2000 2>&1 | grep "2026-07-13" | grep -iE 'ERROR|Exception' | grep -v 'at org\.\|at java\.\|at com\.stokr\.config.GlobalException' | tail -20

echo ""
echo "=== BACKEND STARTUP ==="
docker logs stokr-lite-backend --tail 3000 2>&1 | grep -iE 'Started|Initializing|Scheduler|schedule|@Scheduled|cron|token|reconcil' | tail -15

echo ""
echo "=== ALL DEPLOYMENTS STATUS ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT d.id, s.name, d.status, d.mode, d.broker_account_id, d.last_scan_at::text, d.next_scan_at::text FROM deployments d JOIN strategies s ON d.strategy_id=s.id ORDER BY d.id;"

echo ""
echo "=== SIGNALS TODAY ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, deployment_id, created_at::text FROM strategy_signals WHERE created_at >= '2026-07-13' ORDER BY created_at DESC;"

echo ""
echo "=== ORDERS TODAY ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, broker_order_id, created_at::text FROM orders WHERE created_at >= '2026-07-13' ORDER BY created_at DESC;"

echo ""
echo "=== TRADES TABLE ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "\d trades;" 2>/dev/null || echo "No trades table"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT * FROM trades ORDER BY created_at DESC LIMIT 5;" 2>/dev/null || echo "No trades or error"
