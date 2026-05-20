-- Ensure strategy_signals.expired is never null for runtime inserts.

UPDATE strategy_signals
SET expired = FALSE
WHERE expired IS NULL;

ALTER TABLE strategy_signals
    ALTER COLUMN expired SET DEFAULT FALSE;

ALTER TABLE strategy_signals
    ALTER COLUMN expired SET NOT NULL;

