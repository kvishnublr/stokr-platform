#!/bin/bash
echo "=== CURRENT OPEN POSITIONS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT o.symbol, o.side, o.execution_mode, o.strategy_key, o.state, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, age(NOW(), o.created_at) as age FROM oms_orders o WHERE o.state='FILLED' AND o.paired_order_id IS NULL ORDER BY o.created_at DESC LIMIT 20;"

echo ""
echo "=== TOTAL OPEN ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) FROM oms_orders WHERE state='FILLED' AND paired_order_id IS NULL;"

echo ""
echo "=== VERIFY DEPLOYED CODE ==="
docker exec stokr-api grep -r "LOOKBACK_HOURS" /app/stokr-strategy-*.jar 2>/dev/null || docker exec stokr-api sh -c "cd /tmp && jar xf /app/stokr-bootstrap.jar BOOT-INF/lib/stokr-strategy-*.jar 2>/dev/null; strings BOOT-INF/lib/stokr-strategy-*.jar 2>/dev/null | grep LOOKBACK_HOURS | head -3"
echo "---"
docker exec stokr-api sh -c "cd /tmp; jar xf /app/stokr-bootstrap.jar BOOT-INF/lib/stokr-strategy-1.0.0-SNAPSHOT.jar BOOT-INF/classes/com/stokr/strategy/service/PressureSmartExitService.class 2>/dev/null; strings BOOT-INF/classes/com/stokr/strategy/service/PressureSmartExitService.class | grep 'LOOKBACK' 2>/dev/null"

echo ""
echo "=== EXIT MONITOR RECENT ==="
tail -10 /var/log/stokr-exit-monitor.log 2>/dev/null

echo ""
echo "=== RECENT EXIT EVENTS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT e.event_type, e.created_at AT TIME ZONE 'Asia/Kolkata' as ist, substring(e.event_payload_json, 1, 80) as payload FROM oms_execution_events e WHERE (e.event_type LIKE '%CLOSE%' OR e.event_type LIKE '%EXIT%' OR e.event_type LIKE '%TARGET%' OR e.event_type LIKE '%STOPLOSS%') AND e.created_at > NOW() - INTERVAL '10 minutes' ORDER BY e.created_at DESC LIMIT 10;"

echo ""
echo "=== SIGNALS with terminal outcome last 10 min ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT outcome_status, count(*) FROM strategy_signals WHERE updated_at > NOW() - INTERVAL '10 minutes' AND outcome_status IS NOT NULL AND outcome_status != '' GROUP BY outcome_status;"
