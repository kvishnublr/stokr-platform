-- Check actual executed trades' edge_after_costs from opportunities table
SELECT o.id, o.underlying, o.strike, o.edge_after_costs, o.action, 
  l.current_pnl, l.target_edge, l.entered_at, l.exited_at
FROM option_arb_opportunities o
JOIN live_positions l ON l.opportunity_id = o.id
WHERE l.current_pnl > 0
ORDER BY o.edge_after_costs DESC;
