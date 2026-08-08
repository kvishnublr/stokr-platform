-- 1. Remove legacy ₹0 P&L positions (bulk-closed with wrong timestamps)
DELETE FROM live_positions WHERE id IN (8,9,10,11,12,13,14,22,23,25,26,30,31,32) AND current_pnl = 0;

-- 2. Close stuck OPEN positions with negative P&L (held since Aug 6) - set exited_at to now
UPDATE live_positions SET status = 'CLOSED', exited_at = NOW()
WHERE id IN (29, 42, 43) AND status = 'OPEN';

-- 3. Show remaining positions
SELECT id, underlying, strike, status, current_pnl, entered_at, exited_at FROM live_positions ORDER BY entered_at;
