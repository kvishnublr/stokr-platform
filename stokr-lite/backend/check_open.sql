SELECT id, underlying, strike, status, target_edge, entered_at, exited_at, current_pnl FROM live_positions WHERE status = 'OPEN' ORDER BY entered_at;
