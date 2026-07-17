#!/bin/bash

echo "=== Marking 10 PENDING orders as REJECTED ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "UPDATE orders SET status='REJECTED', error_message='No broker account at time of order - intraday path fired before fix', updated_at=NOW() WHERE status='PENDING' AND broker_order_id IS NULL;"
echo "Done."

echo ""
echo "=== Verifying ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, error_message, created_at::text FROM orders WHERE status='PENDING';"
echo "(should be empty)"

echo ""
echo "=== Strategy-Universe Mappings ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT s.name, ug.group_key, COUNT(us.symbol) as symbol_count FROM strategy_universe_mappings sum2 JOIN strategies s ON sum2.strategy_id=s.id JOIN universe_groups ug ON sum2.group_id=ug.id LEFT JOIN universe_symbols us ON us.group_id=ug.id AND us.enabled=true GROUP BY s.name, ug.group_key;"

echo ""
echo "=== Recent Backend Activity (last 50 lines) ==="
docker logs stokr-lite-backend --tail 50 2>&1 | grep -iE 'ExecutionEngine|SignalProcessor|SchedulerService|process|scan' | tail -10 || echo "  No execution activity"
