#!/bin/bash
echo "=== EXACT EXIT MONITOR QUERY for LIVE open entries ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.id, o.symbol, o.side, o.strategy_key, o.state, 
  o.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
  o.reject_reason, o.signal_id
FROM oms_orders o
WHERE o.deleted = false
  AND o.state = 'FILLED'
  AND o.execution_mode = 'LIVE'
  AND o.idempotency_key NOT LIKE 'outcome-exit:%'
  AND NOT EXISTS (
    SELECT 1 FROM oms_orders x
    WHERE x.deleted = false
      AND x.idempotency_key LIKE 'outcome-exit:%'
      AND x.symbol = o.symbol
      AND x.user_id = o.user_id
      AND x.created_at > o.created_at
  );
"
echo ""
echo "=== ALL LIVE FILLED orders ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.id, o.symbol, o.side, o.strategy_key, o.state, o.execution_mode,
  o.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
  substring(o.reject_reason, 1, 50) as reason,
  o.signal_id
FROM oms_orders o
WHERE o.state = 'FILLED' AND o.execution_mode = 'LIVE'
ORDER BY o.created_at DESC;
"
echo ""
echo "=== ALL outcome-exit LIVE orders ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.id, o.symbol, o.side, o.strategy_key, o.state,
  o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.idempotency_key LIKE 'outcome-exit:%'
  AND o.execution_mode = 'LIVE'
ORDER BY o.created_at DESC
LIMIT 20;
"
