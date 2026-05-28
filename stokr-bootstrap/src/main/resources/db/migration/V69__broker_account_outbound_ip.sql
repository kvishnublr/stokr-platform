-- Per-trader outbound IP for Zerodha API calls.
-- Zerodha requires each trader to whitelist a unique IP.
-- When multiple traders share one server, each gets a dedicated IP.

ALTER TABLE broker_accounts
    ADD COLUMN outbound_ip VARCHAR(45);

COMMENT ON COLUMN broker_accounts.outbound_ip IS 'Server IP used for outbound Zerodha API calls for this trader. NULL = use default server IP.';
