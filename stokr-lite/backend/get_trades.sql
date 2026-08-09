SELECT id, underlying, strike, action, status, 
  ce_entry_price, pe_entry_price, fut_entry_price,
  ce_exit_price, pe_exit_price, fut_exit_price,
  target_edge, current_pnl, 
  entered_at, exited_at,
  ce_order_id, pe_order_id, fut_order_id,
  error_message
FROM live_positions 
WHERE status IN ('CLOSED','EXITED','OPEN')
ORDER BY entered_at ASC;
