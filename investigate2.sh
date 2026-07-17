#!/bin/bash

echo "=== DOCKER EVENTS AS HUMAN READABLE ==="
docker events --since 2026-07-13T00:00:00 --until 2026-07-14T00:00:00 --filter container=stokr-lite-backend --format '{{.Time}} {{.Action}}' 2>/dev/null | while read ts action; do
  date -d "@$ts" -u '+%Y-%m-%d %H:%M:%S UTC = %TZ' 2>/dev/null || python3 -c "from datetime import datetime, timezone; print(datetime.fromtimestamp($ts, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC') + ' -> ' + datetime.fromtimestamp($ts, tz=timezone.utc).strftime('%H:%M IST') + ' $action')"
done

echo ""
echo "=== PROD_OPS SCRIPT (what does it do?) ==="
head -50 /opt/stokr/stokr-platform/scripts/prod_ops.sh 2>/dev/null || echo "not found"

echo ""
echo "=== TOKEN REFRESH SCRIPT - does it restart backend? ==="
grep -n "restart\|docker\|Restart" /usr/local/bin/zerodha_token_refresh.py 2>/dev/null || echo "not found"

echo ""
echo "=== TOKEN REFRESH FULL LOG ==="
cat /var/log/stokr-token-refresh.log 2>/dev/null

echo ""
echo "=== VALIDATE TRACE SCRIPT (runs every 5 min) ==="
head -30 /opt/stokr/stokr-platform/scripts/validate-trace.py 2>/dev/null || echo "not found"

echo ""
echo "=== EXIT MONITOR SCRIPT (runs every 2 min) ==="
head -50 /opt/stokr/stokr-platform/scripts/exit_monitor.py 2>/dev/null || echo "not found"

echo ""
echo "=== WHEN DID FIRST TOKEN REFRESH HAPPEN? ==="
docker logs stokr-lite-backend 2>&1 | grep -E 'Started|Restart|token|auth|reconcil' | head -20
