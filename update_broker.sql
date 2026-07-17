UPDATE broker_accounts 
SET auto_reconnect = true, 
    zerodha_password = 'Temp12345', 
    zerodha_totp_secret = 'BQW7QISFB4PFA7SV3VSZAQ4B5I4WJUKC' 
WHERE id = 4;

SELECT id, broker_name, client_id, status, auto_reconnect, 
       CASE WHEN zerodha_password IS NOT NULL THEN 'SET' ELSE 'NULL' END as pwd,
       CASE WHEN zerodha_totp_secret IS NOT NULL THEN 'SET' ELSE 'NULL' END as totp
FROM broker_accounts;
