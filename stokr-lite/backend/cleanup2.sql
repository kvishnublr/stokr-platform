DELETE FROM live_positions WHERE id IN (22,23,25,26,30,31,32) AND current_pnl IS NULL;
SELECT COUNT(*) as remaining FROM live_positions;
