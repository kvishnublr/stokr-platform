#!/bin/bash
echo "=== operational_audit_events (last 10) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, event_type, status, 
       substring(details::text, 1, 100) as details
FROM operational_audit_events
ORDER BY created_at DESC
LIMIT 10;"

echo ""
echo "=== operational_session_summary ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT * FROM operational_session_summary ORDER BY updated_at DESC LIMIT 5;"

echo ""
echo "=== DAILY ORDER LIMITS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT * FROM strategy_execution_limits ORDER BY created_at DESC LIMIT 10;"

echo ""
echo "=== STOKR_ENV vars in container ==="
docker exec stokr-api env | grep -iE 'LIMIT|MAX|HALT|SUSPEND|DAILY|THROTTLE|SESSION|LIVE_ENABLED' 2>/dev/null

echo ""
echo "=== HOT RELOAD audit for today ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, event_type, description
FROM audit_log
WHERE created_at > '2026-06-08 00:00 IST'::timestamptz
  AND event_type LIKE '%LIVE%' OR event_type LIKE '%EXEC%' OR event_type LIKE '%HALT%'
ORDER BY created_at DESC
LIMIT 10;"
