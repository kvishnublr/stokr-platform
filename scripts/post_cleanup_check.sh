#!/bin/bash
echo "=== CURRENT STATE AFTER CLEANUP ==="
echo ""
echo "Open FILLED orders (no exit):"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) as open_positions FROM oms_orders WHERE state='FILLED' AND paired_order_id IS NULL;"
echo ""
echo "Open FILLED by execution_mode:"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT execution_mode, count(*) FROM oms_orders WHERE state='FILLED' AND paired_order_id IS NULL GROUP BY execution_mode;"
echo ""
echo "Exit monitor latest:"
tail -15 /var/log/stokr-exit-monitor.log
echo ""
echo "=== VERIFY CODE: check source .env and last commit ==="
cd /opt/stokr/stokr-platform && cat .env | grep REPLAY
cd /opt/stokr/stokr-platform && git log --oneline -1
cd /opt/stokr/stokr-platform && git log --oneline --all | head -5
echo ""
echo "=== CHECK LOOKBACK in source ==="
grep -n "LOOKBACK_HOURS\|EXPIRY_HOURS\|private static final int" stokr-strategy/src/main/java/com/stokr/strategy/service/PressureSmartExitService.java 2>/dev/null | head -5
grep -n "EXPIRY_HOURS\|private static final int" stokr-strategy/src/main/java/com/stokr/strategy/service/SignalOutcomeTrackerService.java 2>/dev/null | head -5
