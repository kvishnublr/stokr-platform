#!/bin/bash
echo "=== BROKER STATUS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT * FROM broker_connection_status ORDER BY updated_at DESC LIMIT 5;"

echo ""
echo "=== CHECK IF BROKER HALT IS STILL ACTIVE ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT * FROM broker_operational_events ORDER BY created_at DESC LIMIT 5;"

echo ""
echo "=== LIVE ORDERS TODAY - ALL STATES ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT state, count(*) as cnt FROM oms_orders 
WHERE execution_mode='LIVE' AND created_at > '2026-06-08 00:00 IST'::timestamptz
GROUP BY state ORDER BY cnt DESC;"

echo ""
echo "=== PAPER ORDERS TODAY - ALL STATES ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT state, count(*) as cnt FROM oms_orders 
WHERE execution_mode='PAPER' AND created_at > '2026-06-08 00:00 IST'::timestamptz
GROUP BY state ORDER BY cnt DESC;"

echo ""
echo "=== RECENT EXIT EVENTS (past 5 min) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT e.event_type, e.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
       substring(e.event_payload_json, 1, 80) as payload
FROM oms_execution_events e
WHERE (e.event_type LIKE '%CLOSE%' OR e.event_type LIKE '%EXIT%' OR e.event_type LIKE '%TARGET%' OR e.event_type LIKE '%STOPLOSS%')
  AND e.created_at > NOW() - INTERVAL '5 minutes'
ORDER BY e.created_at DESC
LIMIT 10;"

echo ""
echo "=== SIGNAL OUTCOMES LAST 5 MIN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "
SELECT s.outcome_status, count(*) as cnt
FROM strategy_signals s
WHERE s.updated_at > NOW() - INTERVAL '5 minutes'
  AND s.outcome_status IS NOT NULL AND s.outcome_status != ''
GROUP BY s.outcome_status;"

echo ""
echo "=== EXIT MONITOR LOG ==="
tail -10 /var/log/stokr-exit-monitor.log
