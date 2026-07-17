UPDATE broker_accounts
SET 
    zerodha_password = (SELECT zerodha_password FROM broker_accounts WHERE id = 3),
    zerodha_totp_secret = (SELECT zerodha_totp_secret FROM broker_accounts WHERE id = 3),
    auto_reconnect = true
WHERE id = 1;

SELECT id, client_id, status, auto_reconnect, 
       zerodha_password IS NOT NULL as has_password,
       zerodha_totp_secret IS NOT NULL as has_totp
FROM broker_accounts WHERE id = 1;
