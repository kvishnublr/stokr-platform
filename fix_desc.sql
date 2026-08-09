-- Fix old signals that still show SELL CE+PE / SELL FUT as description
-- These were saved BEFORE the parity leg fix
-- For MIDCPNIFTY with edge > 0 (conversion): should be "BUY CE+PE / SELL FUT"
-- For NIFTY with edge > 0 (conversion): should be "BUY CE+PE / SELL FUT"

-- First check what the old records look like
SELECT id, underlying, strike, action, description, edge_points, ce_entry_price, pe_entry_price, futures_price
FROM option_arb_opportunities
WHERE strategy_type = 'BID_PARITY'
AND action LIKE '%SELL CE+PE%'
AND status IN ('RUNNING', 'DETECTED', 'OPEN')
ORDER BY id DESC
LIMIT 10;
