#!/bin/bash
echo "--- Check server logs for history requests ---"
journalctl -u stokr-lite --since "5 minutes ago" --no-pager 2>&1 | grep -i "history\|signal" | tail -20

echo "--- Check if there are duplicate endpoints ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*), DATE(scan_time AT TIME ZONE 'Asia/Kolkata') as d 
FROM option_arb_opportunities 
WHERE scan_time >= '2026-08-03' AND scan_time <= '2026-08-09'
GROUP BY d ORDER BY d;
"

echo "--- Test frontend URL ---"
curl -s -v "http://173.249.55.84:8081/api/option-arbitrage/history?page=0&size=10&startDate=2026-08-03&endDate=2026-08-09" 2>&1 | head -30
