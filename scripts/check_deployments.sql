-- Check ALL deployments and their statuses
SELECT d.id, d.strategy_id, s.name as strategy_name, s.category, s.timeframe, d.status, d.mode, d.capital,
       d.created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as created_ist,
       d.updated_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as updated_ist
FROM deployments d
JOIN strategies s ON d.strategy_id = s.id
ORDER BY d.id;

-- Check strategy table statuses
SELECT id, name, status, category, timeframe FROM strategies WHERE status != 'DELETED' ORDER BY id;

-- Check recent application/error logs
SELECT * FROM error_logs ORDER BY created_at DESC LIMIT 10;
