#!/bin/bash
echo "=== ALL 17 LIVE REJECTIONS TODAY ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.symbol, o.side, 
       o.reject_reason, o.strategy_key
FROM oms_orders o
WHERE o.execution_mode='LIVE' AND o.state='REJECTED'
  AND o.created_at > '2026-06-08 00:00 IST'::timestamptz
ORDER BY o.created_at;"

echo ""
echo "=== CHECK broker operational state tables ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT table_name FROM information_schema.tables 
WHERE table_schema='public' 
  AND (table_name LIKE '%broker%halt%' OR table_name LIKE '%operational%' 
       OR table_name LIKE '%broker%state%' OR table_name LIKE '%broker%status%'
       OR table_name LIKE '%execution%halt%' OR table_name LIKE '%trading%halt%')
ORDER BY table_name;"

echo ""
echo "=== CHECK app_config for broker halt ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT key, value FROM app_config
WHERE key LIKE '%broker%' OR key LIKE '%halt%' OR key LIKE '%operational%'
ORDER BY key;"

echo ""
echo "=== CURRENT positions (open) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.execution_mode, o.state,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.state IN ('FILLED','PENDING_SUBMISSION','ACCEPTED')
  AND o.paired_order_id IS NULL
ORDER BY o.created_at DESC
LIMIT 15;"
