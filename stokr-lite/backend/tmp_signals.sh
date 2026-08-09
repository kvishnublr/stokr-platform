#!/bin/bash
echo "=== Recent signals with edges ==="
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "
SELECT underlying, strike, edge_after_costs, strategy_type, action
FROM option_arb_opportunities
WHERE scan_time > NOW() - INTERVAL '15 minutes'
AND edge_after_costs > 200
ORDER BY edge_after_costs DESC
LIMIT 15;
"
