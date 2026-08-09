#!/bin/bash
echo "--- Direct DB query for Aug 3-9 ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*) FROM option_arb_opportunities 
WHERE scan_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-09 23:59:59';
"

echo "--- With page limit ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*) FROM option_arb_opportunities 
WHERE scan_time >= '2026-08-03 00:00:00' AND scan_time <= '2026-08-09 23:59:59.999999';
"

echo "--- Check scan_time timezone ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT id, scan_time AT TIME ZONE 'Asia/Kolkata' as ist_time, underlying 
FROM option_arb_opportunities 
WHERE id = 261112;
"

echo "--- Test API with proper URL encoding ---"
curl -s "http://127.0.0.1:8081/api/option-arbitrage/history?page=0&size=10&startDate=2026-08-03&endDate=2026-08-09" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('items:', len(d.get('items', [])))
print('totalElements:', d.get('totalElements'))
print('count:', d.get('count'))
"
