#!/bin/bash
echo "=== KILL SWITCH ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT id, reason, active, created_at AT TIME ZONE 'Asia/Kolkata' as ist FROM trading_kill_switch_events ORDER BY id DESC LIMIT 5;"
echo "=== API LOGS TAIL ==="
docker logs stokr-api --tail 5 2>&1
echo "=== WAITING 20s ==="
sleep 20
echo "=== API HEALTH ==="
curl -s -o /dev/null -w "%{http_code}" --connect-timeout 10 http://localhost:8080/api/health 2>/dev/null
echo ""
