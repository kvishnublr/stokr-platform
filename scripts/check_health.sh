#!/bin/bash
sleep 30
echo "=== API HEALTH ==="
curl -s -o /dev/null -w "%{http_code}" --connect-timeout 10 http://localhost:8080/api/health 2>/dev/null
echo ""
echo "=== KILL SWITCH ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT id, reason, active, triggered_at AT TIME ZONE 'Asia/Kolkata' as ist FROM trading_kill_switch_events ORDER BY id DESC LIMIT 5;"
echo "=== OPEN POSITIONS + CURRENT PNL ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.strategy_key, 
       x.avg_price as fill_price,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as opened,
       age(NOW(), o.created_at) as age
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.paired_order_id IS NULL
ORDER BY o.symbol;
"
