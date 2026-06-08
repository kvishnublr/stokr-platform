#!/bin/bash
echo "=== RECENT LIVE ORDERS (last 30 min) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.symbol, o.side, 
       o.state, o.reject_reason, o.strategy_key
FROM oms_orders o
WHERE o.execution_mode='LIVE'
  AND o.created_at > NOW() - INTERVAL '30 minutes'
ORDER BY o.created_at DESC
LIMIT 20;"

echo ""
echo "=== SIGNALS LAST 10 MIN (correct columns) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT s.created_at AT TIME ZONE 'Asia/Kolkata' as ist, s.symbol, s.signal_type, 
       s.outcome_status
FROM strategy_signals s
WHERE s.created_at > NOW() - INTERVAL '10 minutes'
ORDER BY s.created_at DESC
LIMIT 15;"

echo ""
echo "=== EXIT EVENTS LAST 10 MIN (correct columns) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT e.event_type, e.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
       substring(e.event_payload_json, 1, 100) as payload
FROM oms_execution_events e
WHERE e.created_at > NOW() - INTERVAL '10 minutes'
ORDER BY e.created_at DESC
LIMIT 10;"

echo ""
echo "=== SIGNAL OUTCOMES LAST 10 MIN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT s.outcome_status, count(*) as cnt
FROM strategy_signals s
WHERE s.updated_at > NOW() - INTERVAL '10 minutes'
  AND s.outcome_status IS NOT NULL AND s.outcome_status != ''
GROUP BY s.outcome_status
ORDER BY cnt DESC;"
