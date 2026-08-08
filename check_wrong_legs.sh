#!/bin/bash
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
-- Count old BID_PARITY records with wrong legs (both CE+PE sold or both bought)
SELECT COUNT(*) as wrong_legs_count
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY'
AND legs LIKE '%SELL%CE%SELL%PE%'
AND status IN ('RUNNING','DETECTED','OPEN');
"

PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*) as wrong_legs_count2
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY'
AND legs LIKE '%BUY%CE%BUY%PE%'
AND status IN ('RUNNING','DETECTED','OPEN');
"

echo "---ALL BID_PARITY count---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*) as total_bidparity
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY';
"

echo "---Sample wrong legs---"
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "
SELECT id, action, legs, strike, underlying
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY'
AND legs LIKE '%SELL%CE%SELL%PE%'
AND status IN ('RUNNING','DETECTED','OPEN')
LIMIT 5;
"
