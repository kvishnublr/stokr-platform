#!/bin/bash
echo "=== STUCK POSITIONS BY EXECUTION MODE ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT o.execution_mode, o.strategy_key, count(*) as cnt FROM oms_orders o WHERE o.state='FILLED' AND o.paired_order_id IS NULL AND o.created_at < NOW() - INTERVAL '24 hours' GROUP BY o.execution_mode, o.strategy_key ORDER BY cnt DESC LIMIT 15;"

echo ""
echo "=== TOTAL STUCK ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) FROM oms_orders o WHERE o.state='FILLED' AND o.paired_order_id IS NULL AND o.created_at < NOW() - INTERVAL '24 hours';"

echo ""
echo "=== TOTAL STUCK (LIVE mode only) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) FROM oms_orders o WHERE o.state='FILLED' AND o.paired_order_id IS NULL AND o.created_at < NOW() - INTERVAL '24 hours' AND o.execution_mode='LIVE';"

echo ""
echo "=== LTP CHECK FOR stuck LIVE positions ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT o.symbol, o.side, o.strategy_key, x.avg_price as fill, o.created_at AT TIME ZONE 'Asia/Kolkata' as opened FROM oms_orders o JOIN oms_executions x ON x.order_id = o.id WHERE o.state='FILLED' AND o.paired_order_id IS NULL AND o.created_at < NOW() - INTERVAL '24 hours' AND o.execution_mode='LIVE' ORDER BY o.symbol;"
