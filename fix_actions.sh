#!/bin/bash
echo "Updating old BID_PARITY action labels..."
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
UPDATE option_arb_opportunities 
SET action = 'BUY FUT + SELL CE + BUY PE'
WHERE strategy_type = 'BID_PARITY'
AND action = 'BUY FUT / SELL CE+PE';
"

PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
UPDATE option_arb_opportunities 
SET action = 'BUY CE + SELL PE + SELL FUT'
WHERE strategy_type = 'BID_PARITY'
AND action = 'BUY CE+PE / SELL FUT';
"

echo "Verifying..."
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT action, COUNT(*) as cnt 
FROM option_arb_opportunities 
WHERE strategy_type = 'BID_PARITY' 
GROUP BY action;
"
