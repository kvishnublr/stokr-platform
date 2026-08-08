#!/bin/bash
echo "=== Trigger scan ==="
curl -s 'http://127.0.0.1:8081/api/option-arbitrage/scan?underlying=ALL' > /dev/null

echo "=== Wait for execution logs ==="
sleep 8

echo "=== Auto-exec logs ==="
grep -iE 'SIGNAL|FIRING|NAVIA|margin|order|place|EXEC|SKIP|MARGIN|auto-exec' /opt/stokr/stokr-lite.log | tail -25
