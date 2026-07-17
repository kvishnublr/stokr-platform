SELECT id, strategy_id, status, mode, capital FROM deployments WHERE status = 'ACTIVE' ORDER BY id;

-- Check the Oversold Bounce strategy class and timeframe
SELECT id, name, strategy_class, timeframe, enabled FROM strategies WHERE id = 15;

-- Check if there are any signals generated today
SELECT count(*), min(created_at), max(created_at) FROM strategy_signals WHERE created_at::date = CURRENT_DATE;

-- Check the full scan cycle log for today
SELECT deployment_id, status, count(*) FROM strategy_signals WHERE created_at::date = CURRENT_DATE GROUP BY deployment_id, status;
