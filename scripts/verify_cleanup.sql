SELECT count(*) AS still_open_live_orphans FROM oms_orders WHERE state = 'FILLED' AND execution_mode = 'LIVE' AND signal_id IS NULL;
SELECT symbol, side, execution_mode, state, created_at AT TIME ZONE 'Asia/Kolkata' AS ist FROM oms_orders WHERE state = 'FILLED' ORDER BY created_at DESC;
