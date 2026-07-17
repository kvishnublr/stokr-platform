SELECT id, deployment_id, symbol, side, left(reason, 80) as reason, status, signal_source, scanner_name, created_at 
FROM strategy_signals ORDER BY id DESC LIMIT 20;
