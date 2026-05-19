-- V32: add default_symbols column to strategy_definitions
-- Stores comma-separated canonical symbol names for stock-specific strategies
-- e.g. 'RELIANCE,TCS,INFY' or null for universe-agnostic strategies

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS default_symbols TEXT;
