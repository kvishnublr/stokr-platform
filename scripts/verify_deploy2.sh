#!/bin/bash
echo "=== CHECK LOOKBACK IN DEPLOYED CODE ==="
docker exec stokr-api sh -c "cd /tmp && jar xf /app/stokr-bootstrap.jar 2>/dev/null; jar xf BOOT-INF/lib/stokr-strategy-1.0.0-SNAPSHOT.jar 2>/dev/null; grep -a 'LOOKBACK' com/stokr/strategy/service/PressureSmartExitService.class 2>/dev/null | od -c | head -3"
echo ""
echo "=== ALTERNATIVE: check the git commit ==="
cd /opt/stokr/stokr-platform && git log --oneline -3
echo ""
echo "=== CHECK JAVA CLASS BYTES ==="
docker exec stokr-api sh -c "apk add --no-cache grep 2>/dev/null; cd /tmp && jar xf /app/stokr-bootstrap.jar 2>/dev/null; jar xf BOOT-INF/lib/stokr-strategy-1.0.0-SNAPSHOT.jar 2>/dev/null; grep -a 'LOOKBACK' com/stokr/strategy/service/PressureSmartExitService.class 2>/dev/null; grep -a 'EXPIRY' com/stokr/strategy/service/SignalOutcomeTrackerService.class 2>/dev/null"
echo ""
echo "=== CURRENT OPEN POSITIONS ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) FROM oms_orders WHERE state='FILLED' AND paired_order_id IS NULL;"
