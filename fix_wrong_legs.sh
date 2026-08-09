#!/bin/bash
# Fix old BID_PARITY legs in DB
# Old wrong legs: "SELL X CE | SELL X PE | BUY FUT" (both options sold = unlimited risk)
# Correct legs for "BUY FUT / SELL CE+PE" (reversal): SELL CE, BUY PE, BUY FUT
# Correct legs for "BUY CE+PE / SELL FUT" (conversion): BUY CE, SELL PE, SELL FUT

echo "Fixing legs for BUY FUT / SELL CE+PE (reversal)..."
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
UPDATE option_arb_opportunities 
SET legs = 'SELL ' || strike || ' CE @ ' || ce_entry_price || ' | BUY ' || strike || ' PE @ ' || pe_entry_price || ' | BUY ' || underlying || ' FUT @ ' || futures_price
WHERE strategy_type = 'BID_PARITY'
AND action LIKE 'BUY FUT%'
AND legs LIKE '%SELL%CE%SELL%PE%'
"

echo "Fixing legs for BUY CE+PE / SELL FUT (conversion)..."
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
UPDATE option_arb_opportunities 
SET legs = 'BUY ' || strike || ' CE @ ' || ce_entry_price || ' | SELL ' || strike || ' PE @ ' || pe_entry_price || ' | SELL ' || underlying || ' FUT @ ' || futures_price
WHERE strategy_type = 'BID_PARITY'
AND action LIKE 'BUY CE%'
AND legs LIKE '%BUY%CE%BUY%PE%'
"

echo "Verifying fix..."
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -c "
SELECT COUNT(*) as still_wrong
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY'
AND (legs LIKE '%SELL%CE%SELL%PE%' OR legs LIKE '%BUY%CE%BUY%PE%');
"

echo "Sample fixed records..."
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "
SELECT id, action, legs
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY'
AND id >= 261100
ORDER BY id
LIMIT 10;
"
