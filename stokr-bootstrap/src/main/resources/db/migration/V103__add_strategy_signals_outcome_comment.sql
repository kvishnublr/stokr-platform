ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS outcome_comment VARCHAR(500);
