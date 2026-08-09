#!/bin/bash
echo "--- Test history API for This Week (Aug 3-9) ---"
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?startDate=2026-08-03&endDate=2026-08-09&size=5" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('Keys:', list(d.keys()))
print('totalCount:', d.get('totalCount'))
print('signals count:', len(d.get('signals', [])))
print('startDate:', d.get('startDate'))
print('endDate:', d.get('endDate'))
if d.get('signals'):
    print('First signal:', d['signals'][0].get('action'), d['signals'][0].get('underlying'))
"

echo "--- Test history API for Yesterday ---"
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?startDate=2026-08-08&endDate=2026-08-08&size=5" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('totalCount:', d.get('totalCount'))
print('signals count:', len(d.get('signals', [])))
"
