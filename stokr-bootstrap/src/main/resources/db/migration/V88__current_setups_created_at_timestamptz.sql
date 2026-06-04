-- Align current_setups.created_at with Instant mapping used by Hibernate
-- This avoids schema-validation failures during bootstrap.

ALTER TABLE current_setups
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
    USING created_at AT TIME ZONE 'UTC';

ALTER TABLE current_setups
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
