SELECT 'LIVE_OPEN' as label, count(*)::text AS val FROM oms_orders WHERE state = 'FILLED' AND execution_mode = 'LIVE';
SELECT 'TOTAL_OPEN' as label, count(*)::text AS val FROM oms_orders WHERE state = 'FILLED';
SELECT symbol, side, execution_mode, state, created_at AT TIME ZONE 'Asia/Kolkata' as ist FROM oms_orders WHERE state = 'FILLED' AND execution_mode = 'LIVE';
