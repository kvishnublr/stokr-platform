SELECT id, client_id, status, access_token IS NOT NULL as has_token, refresh_token IS NOT NULL as has_refresh, token_expiry, auto_reconnect FROM broker_accounts ORDER BY id;
