#!/bin/bash
echo "--- Test exact frontend URL for This Week ---"
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?page=0&size=50000&startDate=2026-08-04&endDate=2026-08-10" | python3 -c "
import sys, json
d = json.load(sys.stdin)
items = d.get('items', [])
print(f'{len(items)} items, {d.get(\"totalElements\")} total')
if items:
    print(f'First action: {items[0].get(\"action\")}')
    print(f'First edgeAfterCosts: {items[0].get(\"edgeAfterCosts\")}')
    print(f'First status: {items[0].get(\"status\")}')
    # Check what edgeAfterCosts values look like
    edges = [i.get('edgeAfterCosts', 0) for i in items[:5]]
    print(f'First 5 edges: {edges}')
"

echo "--- Check if there are records from Aug 8+ (today/weekend) ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT DATE(scan_time) as d, COUNT(*) FROM option_arb_opportunities 
WHERE scan_time >= '2026-08-08' GROUP BY d;
"
