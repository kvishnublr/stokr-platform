SELECT id, broker_name, client_id, status, auto_reconnect, 
       zerodha_password IS NOT NULL as has_password,
       zerodha_totp_secret IS NOT NULL as has_totp,
       last_auto_reconnect,
       token_expiry,
       access_token IS NOT NULL as has_token
FROM broker_accounts
WHERE broker_name = 'ZERODHA'
ORDER BY id;
