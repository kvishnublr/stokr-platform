-- Fix: EXPIRED opportunities should NOT have P&L set (they were never traded)
-- Only DETECTED and ACTIVE opps should show theoretical P&L
UPDATE option_arb_opportunities SET pnl_after_costs = NULL WHERE status = 'EXPIRED';

-- Also update today's OPEN scan results with theoretical P&L
UPDATE option_arb_opportunities SET pnl_after_costs = edge_after_costs WHERE status = 'OPEN' AND pnl_after_costs IS NULL;
