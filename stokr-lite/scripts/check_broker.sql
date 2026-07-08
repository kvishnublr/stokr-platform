SELECT broker_type, access_token IS NOT NULL as has_token, token_expiry FROM brokers;
