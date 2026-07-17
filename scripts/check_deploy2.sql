-- Check ALL deployments and their statuses
SELECT d.id, d.strategy_id, s.name as strategy_name, s.timeframe, d.status, d.mode, d.capital,
       d.created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as created_ist,
       d.updated_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as updated_ist
FROM deployments d
JOIN strategies s ON d.strategy_id = s.id
ORDER BY d.id;

-- Check strategy columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'strategies' ORDER BY ordinal_position;

-- Check strategies table
SELECT * FROM strategies WHERE name IS NOT NULL ORDER BY id;

-- Check docker logs for today
