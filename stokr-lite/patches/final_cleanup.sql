-- Clear P&L from all EXPIRED entries (they were never traded)
UPDATE option_arb_opportunities SET pnl_after_costs = NULL WHERE status = 'EXPIRED';

-- Verify final state
SELECT status, COUNT(*), SUM(COALESCE(pnl_after_costs,0)) as total_pnl, SUM(COALESCE(edge_after_costs,0)) as total_edge FROM option_arb_opportunities GROUP BY status;
