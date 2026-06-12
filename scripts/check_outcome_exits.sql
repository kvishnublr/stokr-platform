SELECT id, symbol, side, state, execution_mode, idempotency_key,
       created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders
WHERE symbol IN ('ICICIBANK', 'M&M')
  AND idempotency_key LIKE 'outcome-exit:%'
  AND deleted = false
ORDER BY created_at;
