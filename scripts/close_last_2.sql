UPDATE oms_orders o
SET state = 'CANCELLED',
    reject_reason = 'POSITION_SWEEP: orphan order no signal linkage, stale from ' || o.created_at::date::text,
    updated_at = NOW()
WHERE o.id IN (
  '236fd8bd-8b03-4a91-9cee-f397ce1573f1',
  'db259b1b-6541-4007-87f0-93d7b56d66d6'
);
