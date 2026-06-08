SELECT 'LIVE_OPEN' as label, count(*)::text AS val FROM oms_orders WHERE state = 'FILLED' AND execution_mode = 'LIVE';
SELECT 'TOTAL_OPEN' as label, count(*)::text AS val FROM oms_orders WHERE state = 'FILLED';
