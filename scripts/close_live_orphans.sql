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
