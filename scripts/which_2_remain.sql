SELECT id, symbol, side, strategy_key, state, execution_mode,
       signal_id IS NOT NULL as has_signal,
       created_at AT TIME ZONE 'Asia/Kolkata' as ist,
       idempotency_key
FROM oms_orders
WHERE state = 'FILLED'
  AND execution_mode = 'LIVE'
ORDER BY created_at;
