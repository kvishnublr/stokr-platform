#!/bin/bash
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "
SELECT id, underlying, strike, action, legs
FROM option_arb_opportunities
WHERE underlying='MIDCPNIFTY' AND strike=14975 AND action LIKE '%BUY FUT%'
AND status IN ('RUNNING','DETECTED','OPEN')
ORDER BY id DESC
LIMIT 3;
"
