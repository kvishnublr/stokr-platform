#!/bin/bash
echo "=== WAITING FOR API (up to 5 min) ==="
for i in $(seq 1 10); do
  sleep 30
  code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 http://localhost:8080/api/health 2>/dev/null)
  echo "Attempt $i: HTTP $code"
  if [ "$code" = "200" ]; then
    echo "API IS HEALTHY"
    break
  fi
done

echo ""
echo "=== KILL SWITCH STATUS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT id, reason, active, created_at AT TIME ZONE 'Asia/Kolkata' as ist 
FROM trading_kill_switch_events ORDER BY id DESC LIMIT 5;"

echo ""
echo "=== STUCK POSITIONS (older than 1 day) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT o.symbol, o.side, o.strategy_key, 
       x.avg_price as fill, o.state, o.created_at AT TIME ZONE 'Asia/Kolkata' as opened,
       date_part('day', NOW() - o.created_at) || ' days' as age
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.state='FILLED' AND o.paired_order_id IS NULL
  AND o.created_at < NOW() - INTERVAL '24 hours'
ORDER BY o.created_at;"

echo ""
echo "=== VALIDATION SCRIPT STATUS ==="
tail -5 /var/log/stokr-validation.log 2>/dev/null || echo "No validation log"

echo ""
echo "=== DOCKER CONTAINERS ==="
docker ps --format "{{.Names}} {{.Status}}"
