SELECT id,underlying,strike,action,bid_type,status,left(notes,50) FROM option_arb_executed_trades ORDER BY id DESC LIMIT 10;
