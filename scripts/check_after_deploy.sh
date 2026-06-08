#!/bin/bash
echo "=== SIGNALS LAST 10 MIN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT s.created_at AT TIME ZONE 'Asia/Kolkata' as ist, s.symbol, s.signal_type, 
       s.strategy_key, s.outcome_status, s.execution_mode,
       CASE WHEN s.id IN (SELECT signal_id FROM oms_orders WHERE signal_id IS NOT NULL) THEN 'ORDERED' ELSE 'NO_ORDER' END as has_order
FROM strategy_signals s
WHERE s.created_at > NOW() - INTERVAL '10 minutes'
ORDER BY s.created_at DESC
LIMIT 20;"

echo ""
echo "=== ORDERS LAST 10 MIN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.symbol, o.side, 
       o.state, o.reject_reason, o.execution_mode, o.strategy_key
FROM oms_orders o
WHERE o.created_at > NOW() - INTERVAL '10 minutes'
ORDER BY o.created_at DESC
LIMIT 20;"

echo ""
echo "=== EXIT EVENTS LAST 10 MIN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT e.event_type, e.occurred_at AT TIME ZONE 'Asia/Kolkata' as ist,
       substring(e.event_payload_json, 1, 100) as payload
FROM oms_execution_events e
WHERE e.occurred_at > NOW() - INTERVAL '10 minutes'
ORDER BY e.occurred_at DESC
LIMIT 10;"

echo ""
echo "=== CURRENT OPEN POSITIONS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.execution_mode, o.strategy_key,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
       age(NOW(), o.created_at) as age
FROM oms_orders o
WHERE o.state='FILLED' AND o.paired_order_id IS NULL
ORDER BY o.created_at DESC
LIMIT 10;"
