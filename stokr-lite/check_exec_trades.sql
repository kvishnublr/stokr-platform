SELECT id, underlying, strike, action, status, edge_at_entry, notes, bid_type, created_at FROM option_arb_executed_trades WHERE created_at >= '2026-07-21' ORDER BY created_at DESC LIMIT 15;
