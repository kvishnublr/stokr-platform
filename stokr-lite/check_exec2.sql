SELECT id, underlying, strike, action, status, edge_at_entry, notes, bid_type, executed_at FROM option_arb_executed_trades WHERE executed_at >= '2026-07-21' ORDER BY executed_at DESC LIMIT 15;
