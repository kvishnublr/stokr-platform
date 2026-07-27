UPDATE broker_accounts 
SET auto_reconnect = true, 
    zerodha_password = '`$ZERODHA_CLIENT_PASSWORD5', 
    zerodha_totp_secret = '`$ZERODHA_TOTP_SECRET' 
WHERE id = 4;

SELECT id, broker_name, client_id, status, auto_reconnect, 
       CASE WHEN zerodha_password IS NOT NULL THEN 'SET' ELSE 'NULL' END as pwd,
       CASE WHEN zerodha_totp_secret IS NOT NULL THEN 'SET' ELSE 'NULL' END as totp
FROM broker_accounts;

