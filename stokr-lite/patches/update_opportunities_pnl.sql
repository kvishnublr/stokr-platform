-- Expire all stale OPEN opportunities from past dates (not today)
UPDATE option_arb_opportunities SET status = 'EXPIRED' WHERE status = 'OPEN' AND scan_time < CURRENT_DATE;

-- Set P&L = edge_after_costs for all DETECTED and ACTIVE opportunities (theoretical P&L)
UPDATE option_arb_opportunities SET pnl_after_costs = edge_after_costs WHERE status IN ('DETECTED', 'ACTIVE') AND pnl_after_costs IS NULL;

-- Set P&L = 0 for EXPIRED opportunities (opportunity passed without execution)
UPDATE option_arb_opportunities SET pnl_after_costs = 0 WHERE status = 'EXPIRED' AND pnl_after_costs IS NULL;

-- Set today's OPEN opportunities to have P&L = edge (they're today's scan results)
UPDATE option_arb_opportunities SET pnl_after_costs = edge_after_costs WHERE status = 'OPEN' AND pnl_after_costs IS NULL AND scan_time >= CURRENT_DATE;

-- Clean up 235 FAILED executed_trades (all failed, NFO wasn't active)
DELETE FROM option_arb_executed_trades WHERE status = 'FAILED';
