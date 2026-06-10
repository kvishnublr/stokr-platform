CREATE TABLE IF NOT EXISTS signal_execution_tracks (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    signal_id       UUID NOT NULL,
    user_id         UUID,
    order_id        UUID,
    strategy_key    VARCHAR(128),
    symbol          VARCHAR(64),
    execution_mode  VARCHAR(16),
    order_state     VARCHAR(64),
    stage           VARCHAR(64) NOT NULL,
    status          VARCHAR(32),
    reason          VARCHAR(512),
    event_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata        JSONB
);

ALTER TABLE signal_execution_tracks
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS signal_id UUID,
    ADD COLUMN IF NOT EXISTS user_id UUID,
    ADD COLUMN IF NOT EXISTS order_id UUID,
    ADD COLUMN IF NOT EXISTS strategy_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS symbol VARCHAR(64),
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16),
    ADD COLUMN IF NOT EXISTS order_state VARCHAR(64),
    ADD COLUMN IF NOT EXISTS stage VARCHAR(64),
    ADD COLUMN IF NOT EXISTS status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS reason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS event_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS metadata JSONB;

CREATE INDEX IF NOT EXISTS idx_signal_execution_tracks_signal
    ON signal_execution_tracks (signal_id, event_time DESC)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_signal_execution_tracks_order
    ON signal_execution_tracks (order_id, event_time DESC)
    WHERE deleted = FALSE AND order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_signal_execution_tracks_day
    ON signal_execution_tracks (event_time DESC, execution_mode, status)
    WHERE deleted = FALSE;
