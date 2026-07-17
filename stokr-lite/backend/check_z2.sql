SELECT id, client_id, status, auto_reconnect, 
       zerodha_password, zerodha_totp_secret,
       last_auto_reconnect, token_expiry
FROM broker_accounts
WHERE broker_name = 'ZERODHA';
