-- Align remaining intraday tables with Hibernate Instant / TIMESTAMPTZ mapping.

ALTER TABLE historical_win_rates
    ALTER COLUMN last_updated TYPE TIMESTAMPTZ
    USING last_updated AT TIME ZONE 'UTC';

ALTER TABLE historical_win_rates
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
    USING created_at AT TIME ZONE 'UTC';

ALTER TABLE historical_win_rates
    ALTER COLUMN last_updated SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE historical_win_rates
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE nse_stocks
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
    USING created_at AT TIME ZONE 'UTC';

ALTER TABLE nse_stocks
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
    USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE nse_stocks
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE nse_stocks
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_trades
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
    USING created_at AT TIME ZONE 'UTC';

ALTER TABLE user_trades
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
    USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE user_trades
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_trades
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
