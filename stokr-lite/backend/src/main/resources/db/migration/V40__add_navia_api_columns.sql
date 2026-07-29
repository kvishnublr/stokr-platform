ALTER TABLE broker_accounts
    ADD COLUMN IF NOT EXISTS navia_api_key    TEXT,
    ADD COLUMN IF NOT EXISTS navia_api_secret TEXT;
