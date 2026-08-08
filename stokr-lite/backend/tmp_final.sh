#!/bin/bash
sleep 15
echo "=== Settings check ==="
curl -s 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings' | python3 -m json.tool

echo ""
echo "=== Trigger scan ==="
curl -s 'http://127.0.0.1:8081/api/option-arbitrage/scan?underlying=ALL' > /dev/null

echo "=== Wait for execution ==="
sleep 10

echo "=== Execution logs ==="
grep -iE 'SIGNAL|FIRING|NAVIA|margin|order|place|EXEC|SKIP|MARGIN|Error|auto-exec' /opt/stokr/stokr-lite.log | grep -v 'Anomaly' | tail -25
