#!/bin/bash
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?strategyType=&startDate=2026-08-03&endDate=2026-08-09&underlying=ALL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('Count:', d.get('count', 0))
items = d.get('items', [])
print('First item keys:', list(items[0].keys()) if items else 'none')
if items:
    i = items[0]
    print('  scanTime:', i.get('scanTime'))
    print('  action:', i.get('action'))
    print('  edgePoints:', i.get('edgePoints'))
    print('  status:', i.get('status'))
    print('  strategyType:', i.get('strategyType'))
"
