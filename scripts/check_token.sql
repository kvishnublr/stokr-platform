-- Check current token status
SELECT id, broker_name, is_active, expires_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as expires_ist,
       created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as created_ist,
       updated_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as updated_ist
FROM broker_accounts ORDER BY id;

-- Check token refresh log
SELECT * FROM broker_token_refresh_log ORDER BY created_at DESC LIMIT 10;

-- Check how many signals total today
SELECT count(*) as total_signals_today FROM strategy_signals WHERE created_at::date = '2026-07-10';

-- Check positions (any open?)
SELECT p.*, s.name as strategy_name FROM positions p LEFT JOIN strategies s ON p.strategy_id = s.id WHERE p.status = 'OPEN' ORDER BY p.id;
