UPDATE live_positions SET status = 'CLOSED', exited_at = NOW() WHERE id IN (44, 45) AND status = 'OPEN';
SELECT id, underlying, strike, status, current_pnl FROM live_positions ORDER BY entered_at DESC LIMIT 5;
