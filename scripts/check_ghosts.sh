#!/bin/bash
echo "=== GHOST POSITIONS: Check if signals exist and their status ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.strategy_key, o.execution_mode,
       s.id as signal_id, s.outcome_status,
       s.created_at AT TIME ZONE 'Asia/Kolkata' as signal_ist,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as order_ist
FROM oms_orders o
LEFT JOIN strategy_signals s ON s.id = o.signal_id
WHERE o.state='FILLED' AND o.paired_order_id IS NULL
  AND o.created_at < NOW() - INTERVAL '24 hours'
ORDER BY o.created_at
LIMIT 30;"

echo ""
echo "=== COUNT ghost positions by outcome status ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT COALESCE(s.outcome_status, 'NO_SIGNAL') as outcome, o.execution_mode, count(*) as cnt
FROM oms_orders o
LEFT JOIN strategy_signals s ON s.id = o.signal_id
WHERE o.state='FILLED' AND o.paired_order_id IS NULL
  AND o.created_at < NOW() - INTERVAL '24 hours'
GROUP BY s.outcome_status, o.execution_mode
ORDER BY cnt DESC;"
