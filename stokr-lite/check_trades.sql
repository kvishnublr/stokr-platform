SELECT id, underlying, strike, action, status, edge_after_costs, notes, bid_type, scan_time FROM option_arb_opportunities WHERE scan_time >= '2026-07-21' ORDER BY scan_time DESC LIMIT 15;
