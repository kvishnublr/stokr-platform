-- =============================================================
-- Stokr Lite - Make signals flexible for Chartink webhooks
-- =============================================================

-- Chartink webhooks arrive before a deployment is created.
-- Make deployment_id nullable so external signals can be stored.
ALTER TABLE strategy_signals ALTER COLUMN deployment_id DROP NOT NULL;

-- Also make user_id and strategy_id nullable for pre-validation signals
ALTER TABLE strategy_signals ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE strategy_signals ALTER COLUMN strategy_id DROP NOT NULL;
