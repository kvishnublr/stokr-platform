#!/bin/bash
echo "=== Open positions ==="
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "SELECT count(*) FROM live_positions WHERE status IN ('OPEN','EXECUTING')"

echo "=== Max positions ==="
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "SELECT setting_value FROM auto_exec_settings WHERE setting_key='maxOpenPositions'"

echo "=== Trigger auto-exec now ==="
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/run' | python3 -m json.tool

echo "=== Check logs for execution ==="
sleep 5
grep -iE 'EXEC|NAVIA|margin|order|place|FAILED|SUCCESS|OPEN|auto-exec' /opt/stokr/stokr-lite.log | tail -15
