#!/bin/bash
echo "=== LIVE ORDERS IN LAST 1 HOUR ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, symbol, side, state, reject_reason, strategy_key
FROM oms_orders 
WHERE execution_mode='LIVE' AND created_at > NOW() - INTERVAL '1 hour'
ORDER BY created_at DESC LIMIT 10;"

echo ""
echo "=== SIGNALS WITH ORDERS (last 15 min) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT s.created_at AT TIME ZONE 'Asia/Kolkata' as ist, s.symbol, s.signal_type, s.outcome_status,
       o.execution_mode, o.state as order_state
FROM strategy_signals s
JOIN oms_orders o ON o.signal_id = s.id
WHERE s.created_at > NOW() - INTERVAL '15 minutes'
ORDER BY s.created_at DESC LIMIT 15;"

echo ""
echo "=== EXIT MONITOR LAST RUN ==="
tail -5 /var/log/stokr-exit-monitor.log
