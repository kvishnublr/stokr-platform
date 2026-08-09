#!/bin/bash
echo "=== Current maxOpenPositions (in-memory vs DB) ==="
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "SELECT setting_value FROM auto_exec_settings WHERE setting_key='maxOpenPositions'"

echo "=== Check recent logs for EXEC or auto-exec ==="
grep -iE 'EXEC|auto-exec|evaluate|place.*order|Navia.*order|margin.*check|NAVIA|Insufficient|FAILED|SQUARED' /opt/stokr/stokr-lite.log | tail -30
