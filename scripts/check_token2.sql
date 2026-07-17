-- Check broker_accounts columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'broker_accounts' ORDER BY ordinal_position;

-- Check broker_accounts data
SELECT * FROM broker_accounts;

-- Check broker_token_refresh_log columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'broker_token_refresh_log' ORDER BY ordinal_position;

-- Check token refresh log
SELECT * FROM broker_token_refresh_log ORDER BY id DESC LIMIT 5;

-- Check positions columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'positions' ORDER BY ordinal_position;

-- Check open positions
SELECT * FROM positions WHERE status = 'OPEN' ORDER BY id;
