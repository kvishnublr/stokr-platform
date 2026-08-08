#!/bin/bash
echo "--- Check Box Spread legs ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "
SELECT id, action, legs, strike, underlying
FROM option_arb_opportunities
WHERE strategy_type = 'BOX_SPREAD'
AND status IN ('RUNNING','DETECTED','OPEN')
LIMIT 5;
"

echo "--- Count Box Spread with wrong patterns ---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*) as total_box FROM option_arb_opportunities WHERE strategy_type = 'BOX_SPREAD';
"
