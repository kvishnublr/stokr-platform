#!/bin/bash
echo "=== CLEANING UP 184 GHOST POSITIONS ==="

# 1. Mark all ghost orders as CANCELLED (no exit order, older than 24h)
docker exec -i stokr-postgres psql -U postgres stokr_platform -c "
UPDATE oms_orders 
SET state = 'CANCELLED', 
    reject_reason = 'GHOST_CLEANUP: stuck open >24h with no exit'
WHERE state = 'FILLED' 
  AND paired_order_id IS NULL 
  AND created_at < NOW() - INTERVAL '24 hours';
"

echo "--- Orders cleaned ---"

# 2. For signals still marked RUNNING and older than 24h, mark as TIME_EXIT
docker exec -i stokr-postgres psql -U postgres stokr_platform -c "
UPDATE strategy_signals 
SET outcome_status = 'TIME_EXIT',
    outcome_time = NOW(),
    realized_pnl = 0,
    exit_price = entry_price
WHERE outcome_status IN ('RUNNING', '')
  AND created_at < NOW() - INTERVAL '24 hours';
"

echo "--- Running signals cleaned ---"

# 3. Verify cleanup
echo ""
echo "=== VERIFY: Remaining ghost positions ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT count(*) FROM oms_orders 
WHERE state = 'FILLED' 
  AND paired_order_id IS NULL 
  AND created_at < NOW() - INTERVAL '24 hours';
"

echo ""
echo "=== VERIFY: Remaining RUNNING signals older than 24h ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT count(*) FROM strategy_signals 
WHERE outcome_status IN ('RUNNING', '')
  AND created_at < NOW() - INTERVAL '24 hours';
"

echo ""
echo "=== CURRENT POSITIONS (should only be today's) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.strategy_key, o.execution_mode,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.state = 'FILLED' 
  AND o.paired_order_id IS NULL
ORDER BY o.created_at DESC
LIMIT 20;
"
