UPDATE live_positions SET exited_at = entered_at WHERE status = 'CLOSED' AND current_pnl = 0 AND exited_at IS NOT NULL AND entered_at IS NOT NULL;
