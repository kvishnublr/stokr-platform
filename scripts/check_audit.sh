#!/bin/bash
echo "=== operational_audit_events (last 10) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, topic, substring(payload_json, 1, 120) as payload FROM operational_audit_events ORDER BY created_at DESC LIMIT 10;"

echo "=== operational_session_summary ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, * FROM operational_session_summary ORDER BY created_at DESC LIMIT 5;"
