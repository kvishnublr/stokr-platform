-- P2 end-of-session operational summary

CREATE TABLE IF NOT EXISTS operational_session_summary (
    id              BIGSERIAL PRIMARY KEY,
    session_date    DATE NOT NULL,
    summary_json    JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_operational_session_summary_date UNIQUE (session_date)
);

CREATE INDEX IF NOT EXISTS idx_operational_session_summary_date
    ON operational_session_summary (session_date DESC);
