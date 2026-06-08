#!/bin/bash
echo "=== CLOSE 10 ORPHAN LIVE POSITIONS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -c "
UPDATE oms_orders o
SET state = 'CANCELLED',
    reject_reason = 'POSITION_SWEEP: orphan order no signal linkage, stale from ' || o.created_at::date::text,
    updated_at = NOW()
WHERE o.deleted = false
  AND o.state = 'FILLED'
  AND o.execution_mode = 'LIVE'
  AND o.signal_id IS NULL
  AND o.idempotency_key NOT LIKE 'outcome-exit:%'
  AND NOT EXISTS (
    SELECT 1 FROM oms_orders x
    WHERE x.deleted = false
      AND x.symbol = o.symbol
      AND x.user_id = o.user_id
      AND x.idempotency_key LIKE 'outcome-exit:%'
      AND x.created_at > o.created_at
  );
"

echo ""
echo "=== VERIFY ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT count(*) FROM oms_orders
WHERE state='FILLED' AND execution_mode='LIVE' AND signal_id IS NULL;
"
echo ""
echo "=== REMAINING OPEN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT symbol, side, execution_mode, state, signal_id IS NOT NULL as has_signal,
  created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders
WHERE state='FILLED' AND paired_order_id IS NULL
ORDER BY created_at;
"
