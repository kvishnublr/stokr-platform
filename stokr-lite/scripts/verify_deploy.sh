#!/bin/bash
echo "=== Deployments ==="
curl -s http://localhost:8081/api/deployments --max-time 5 | python3 -c "
import json, sys
d = json.load(sys.stdin)
for x in d:
    print(f\"#{x['id']} {x['strategyName']} {x['status']} {x['mode']} capital={x['capital']}\")
"

echo ""
echo "=== Frontend ==="
curl -s -o /dev/null -w '%{http_code}' https://stokr.in/ --max-time 5

echo ""
echo "=== Trader page ==="
curl -s -o /dev/null -w '%{http_code}' https://stokr.in/trader --max-time 5
