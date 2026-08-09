#!/bin/bash
echo "--- Test with size=50000 ---"
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?page=0&size=50000&startDate=2026-08-03&endDate=2026-08-09" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('items:', len(d.get('items', [])))
print('totalElements:', d.get('totalElements'))
print('count:', d.get('count'))
"

echo "--- Test with minEdge ---"
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?page=0&size=50000&startDate=2026-08-03&endDate=2026-08-09&minEdge=300" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('items:', len(d.get('items', [])))
print('totalElements:', d.get('totalElements'))
"
