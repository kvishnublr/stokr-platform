#!/bin/bash
echo "=== DO THESE 10 LIVE FILLED ORDERS HAVE SIGNALS? ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.id, o.symbol, o.side, o.strategy_key, o.signal_id, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
  CASE WHEN o.signal_id IS NOT NULL THEN 'HAS_SIGNAL' ELSE 'NO_SIGNAL' END as signal_status
FROM oms_orders o
WHERE o.state = 'FILLED' AND o.execution_mode = 'LIVE'
ORDER BY o.created_at DESC;
"
echo ""
echo "=== CHECK IF EXIT ORDERS EXIST FOR ANY OF THESE ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.id, o.symbol, o.side, o.execution_mode, o.state, o.idempotency_key, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.symbol IN ('CASTROLIND','TITAN','HDFCLIFE','AXISBANK','WIPRO','BAJFINANCE','M&M','DRREDDY','TATASTEEL','ICICIBANK')
  AND o.idempotency_key LIKE 'outcome-exit:%'
  AND o.deleted = false
ORDER BY o.symbol, o.created_at;
"
echo ""
echo "=== ANY EXIT ORDERS ON THESE SYMBOLS AT ALL? ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.execution_mode, o.state, o.idempotency_key, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.symbol IN ('CASTROLIND','TITAN','HDFCLIFE','AXISBANK','WIPRO','BAJFINANCE','M&M','DRREDDY','TATASTEEL','ICICIBANK')
  AND o.state = 'FILLED'
  AND o.execution_mode = 'PAPER'
  AND o.created_at > '2026-06-05'
ORDER BY o.symbol, o.created_at;
"
