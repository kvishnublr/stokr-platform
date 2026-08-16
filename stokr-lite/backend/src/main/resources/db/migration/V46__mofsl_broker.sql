-- Motilal Oswal (MOFSL) broker credentials. Reuses the existing client_id column for their
-- client code; password + TOTP secret get dedicated columns, matching the navia_api_key /
-- navia_api_secret convention already used for Navia.
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS mofsl_password TEXT;
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS mofsl_totp_secret TEXT;
