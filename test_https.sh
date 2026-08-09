#!/bin/bash
curl -s "https://stokr.in/api/option-arbitrage/history?page=0&size=5&startDate=2026-08-04&endDate=2026-08-10" -k | python3 -c "
import sys, json
d = json.load(sys.stdin)
items = d.get('items', [])
print(f'Items: {len(items)}, Total: {d.get(\"totalElements\", 0)}')
"
