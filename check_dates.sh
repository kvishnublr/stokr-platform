#!/bin/bash
echo "--- Records per date ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT DATE(scan_time) as d, COUNT(*) as cnt 
FROM option_arb_opportunities 
GROUP BY DATE(scan_time) 
ORDER BY d DESC 
LIMIT 10;
"

echo "--- Latest records ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT id, scan_time, underlying, action, strategy_type, status, edge_after_costs
FROM option_arb_opportunities
ORDER BY id DESC
LIMIT 5;
"
