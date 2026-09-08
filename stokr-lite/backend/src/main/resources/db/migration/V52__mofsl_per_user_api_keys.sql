-- Move MOFSL API key/secret from application-level env vars to per-user broker_accounts.
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS mofsl_api_key TEXT;
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS mofsl_api_secret TEXT;
