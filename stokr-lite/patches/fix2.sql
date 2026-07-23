UPDATE option_arb_opportunities SET pnl_after_costs = NULL WHERE status = 'EXPIRED';
SELECT status, COUNT(*), COUNT(pnl_after_costs) as with_pnl, SUM(COALESCE(pnl_after_costs,0)) as total_pnl FROM option_arb_opportunities GROUP BY status ORDER BY status;
