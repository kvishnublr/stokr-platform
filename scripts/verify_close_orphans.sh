#!/bin/bash
echo "=== FIND JAR FILES ==="
docker exec stokr-api find /app -name "*.jar" -type f 2>/dev/null | head -10
echo ""
echo "=== EXTRACT AND CHECK LOOKBACK ==="
docker exec stokr-api sh -c "cd /tmp && jar xf /app/stokr-bootstrap.jar BOOT-INF/lib/stokr-strategy-1.0.0-SNAPSHOT.jar 2>/dev/null; jar xf BOOT-INF/lib/stokr-strategy-1.0.0-SNAPSHOT.jar 2>/dev/null; strings stokr/strategy/service/PressureSmartExitService.class 2>/dev/null | grep LOOKBACK; strings stokr/strategy/service/SignalOutcomeTrackerService.class 2>/dev/null | grep EXPIRY"
echo ""
echo "=== REPLAY ENV ==="
grep STOKR_REPLAY /opt/stokr/stokr-platform/.env
echo ""
echo "=== CLOSE 32 ORPHAN PAPER ORDERS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -c "UPDATE oms_orders SET state='CANCELLED', reject_reason='REPLAY_ORPHAN: no signal linkage, closed by cleanup' WHERE state='FILLED' AND paired_order_id IS NULL AND signal_id IS NULL AND created_at < NOW() - INTERVAL '10 minutes';"
echo ""
echo "=== VERIFY CLOSED ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) FROM oms_orders WHERE state='FILLED' AND paired_order_id IS NULL;"
